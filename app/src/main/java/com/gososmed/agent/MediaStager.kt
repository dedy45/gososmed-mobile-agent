package com.gososmed.agent
import com.gososmed.agent.privileged.PrivilegedShellHolder

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Native media staging for BYOD mobile publishing.
 *
 * Downloads a video from [url] into a temporary cache file, verifies byte
 * length and SHA-256 digest, then promotes it to shared storage via
 * MediaStore (API 29+) or a direct DCIM/Camera write with MediaScanner
 * (API < 29). Returns a JSON result compatible with the AgentCommand
 * protocol so callers can build a typed response directly.
 *
 * This runs entirely without AccessibilityService and must be dispatched
 * from an IO thread (ASYNC_BACKGROUND_COMMANDS) — the network call and
 * file copy can block for many seconds on large video files.
 */
object MediaStager {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS    = 30_000
    private const val BUFFER_SIZE        = 64 * 1024  // 64 KiB

    /**
     * Download, verify, and register a media file.
     *
     * @param context       Application context (for cacheDir, contentResolver).
     * @param url           HTTP/HTTPS URL of the media asset.
     * @param filename      Target filename including extension (e.g. "video.mp4").
     * @param expectedSha256 Lowercase hex SHA-256 expected digest; empty = skip check.
     * @param expectedBytes  Expected file size in bytes; 0 = skip check.
     * @return JSONObject with shape:
     *   Success: { ok: true,  result: { uri, path, bytes, sha256 } }
     *   Failure: { ok: false, error: "<reason>" }
     */
    fun stageMedia(
        context: Context,
        url: String,
        filename: String,
        expectedSha256: String,
        expectedBytes: Long,
    ): JSONObject {
        // ── Step 1: stream into a temp file and compute SHA-256 in one pass ──
        val tmpFile = File(context.cacheDir, "stage_${System.currentTimeMillis()}_$filename")
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        try {
            var connection = openConnection(url)

            // Follow redirects manually so we can handle every 3xx code.
            var redirects = 0
            while (true) {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return err("redirect with no Location header (HTTP $code)")
                    connection.disconnect()
                    if (++redirects > 10) return err("too many redirects")
                    connection = openConnection(location)
                    continue
                }
                if (code !in 200..299) {
                    connection.disconnect()
                    return err("HTTP $code from server")
                }
                break
            }

            FileOutputStream(tmpFile).use { fos ->
                connection.inputStream.use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        totalBytes += n
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            tmpFile.delete()
            return err("download failed: ${e.message}")
        }

        // ── Step 2: verify byte length ──
        if (expectedBytes > 0L && totalBytes != expectedBytes) {
            tmpFile.delete()
            return err("byte length mismatch")
        }

        // ── Step 3: verify SHA-256 ──
        val computedHex = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256.isNotBlank() && computedHex != expectedSha256.lowercase()) {
            tmpFile.delete()
            return err("sha256 mismatch")
        }

        // ── Step 4: promote to shared storage ──
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                stageViaMediaStore(context, tmpFile, filename, totalBytes, computedHex)
            } else {
                stageViaLegacy(context, tmpFile, filename, totalBytes, computedHex)
            }
        } finally {
            // Always remove the temp file; MediaStore/legacy copy owns its own bytes.
            tmpFile.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun openConnection(rawUrl: String): HttpURLConnection {
        val conn = URL(rawUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout    = READ_TIMEOUT_MS
        // Disable automatic redirect following so we control Location handling.
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "GoSosmed-MediaStager/1.0")
        return conn
    }

    /** API 29+: insert via MediaStore with IS_PENDING guard. */
    private fun stageViaMediaStore(
        context: Context,
        src: File,
        filename: String,
        totalBytes: Long,
        computedHex: String,
    ): JSONObject {
        val resolver = context.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val contentUri: Uri = resolver.insert(collectionUri, cv)
            ?: return err("MediaStore.insert returned null")

        try {
            resolver.openOutputStream(contentUri)?.use { out ->
                FileInputStream(src).use { it.copyTo(out, BUFFER_SIZE) }
            } ?: return run {
                resolver.delete(contentUri, null, null)
                err("openOutputStream returned null for $contentUri")
            }
        } catch (e: Exception) {
            resolver.delete(contentUri, null, null)
            return err("write to MediaStore stream failed: ${e.message}")
        }

        // Publish: clear IS_PENDING so the file is visible to other apps.
        val clearPending = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(contentUri, clearPending, null, null)

        return ok(contentUri.toString(), contentUri.toString(), totalBytes, computedHex)
    }

    /** API < 29: write to DCIM/Camera/ and trigger MediaScanner. */
    private fun stageViaLegacy(
        context: Context,
        src: File,
        filename: String,
        totalBytes: Long,
        computedHex: String,
    ): JSONObject {
        val dcimCamera = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera"
        )
        dcimCamera.mkdirs()
        val target = File(dcimCamera, filename)

        try {
            FileInputStream(src).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
        } catch (e: Exception) {
            target.delete()
            return err("legacy file copy failed: ${e.message}")
        }

        // Notify the media scanner so the file appears in gallery apps immediately.
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf("video/mp4"),
            null,
        )

        return ok("file://${target.absolutePath}", target.absolutePath, totalBytes, computedHex)
    }

    private fun ok(uri: String, path: String, bytes: Long, sha256: String): JSONObject =
        JSONObject()
            .put("ok", true)
            .put(
                "result",
                JSONObject()
                    .put("uri", uri)
                    .put("path", path)
                    .put("bytes", bytes)
                    .put("sha256", sha256),
            )

    private fun err(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    /**
     * Menghapus seluruh video dan foto sementara hasil staging GoSosmed (gosmed_*, reel_*, staged_*).
     * Menggunakan filter selektif ketat agar TIDAK AKAN PERNAH menghapus foto/video pribadi milik pengguna.
     */
    fun cleanStagedMedia(context: Context): JSONObject {
        var deletedVideos = 0
        var deletedImages = 0
        var deletedFiles = 0

        // 1. Eksekusi pembersihan via PrivilegedShell (Wireless ADB / Root / Shizuku) jika tersedia.
        // Ini adalah cara paling ampuh & tuntas untuk menghapus file fisik + cache .trashed di HyperOS/MIUI
        // lintas-UID tanpa diblokir kebijakan Scoped Storage Android 11+.
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            try {
                val shellCmd = "rm -f /sdcard/DCIM/Camera/*gosmed* /sdcard/DCIM/Camera/.*gosmed* " +
                               "/sdcard/DCIM/Camera/*reel* /sdcard/DCIM/Camera/.*reel* " +
                               "/sdcard/DCIM/Camera/*staged* /sdcard/DCIM/Camera/.*staged* " +
                               "/sdcard/Download/*gosmed* /sdcard/Download/.*gosmed* " +
                               "/sdcard/Download/*reel* /sdcard/Download/.*reel* " +
                               "/sdcard/Download/*staged* /sdcard/Download/.*staged* " +
                               "/sdcard/Movies/*gosmed* /sdcard/Movies/.*gosmed* " +
                               "/sdcard/Movies/*staged* /sdcard/Movies/.*staged*"
                val res = shell.exec(shellCmd, 5000L)
                android.util.Log.i("GoAgent", "cleanStagedMedia: shell rm result ok=${res.ok} failure=${res.failure} out=${res.stdout}")
            } catch (t: Throwable) {
                android.util.Log.w("GoAgent", "cleanStagedMedia: shell exec failed: ${t.message}")
            }
        }

        // 2. Hapus dari MediaStore Videos (Aman & Legal via ContentResolver)
        try {
            val videoSelection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE '%gosmed_%' OR " +
                                 "${MediaStore.Video.Media.DISPLAY_NAME} LIKE '%reel_%' OR " +
                                 "${MediaStore.Video.Media.DISPLAY_NAME} LIKE '%staged_%'"
            deletedVideos = context.contentResolver.delete(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoSelection,
                null
            )
        } catch (t: Throwable) {
            // Ignored / fallback ke pembersihan direktori
        }

        // 3. Hapus dari MediaStore Images
        try {
            val imgSelection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%gosmed_%' OR " +
                               "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%reel_%' OR " +
                               "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%staged_%'"
            deletedImages = context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imgSelection,
                null
            )
        } catch (t: Throwable) {
            // Ignored
        }

        // 4. Sapu file fisik sisa di direktori DCIM/Camera, Download, dan Movies
        val targetDirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ""),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "")
        )

        val pathsToRescan = mutableListOf<String>()
        for (dir in targetDirs) {
            if (dir.exists() && dir.isDirectory) {
                val matches = dir.listFiles { file ->
                    file.name.contains("gosmed_") || file.name.contains("reel_") || file.name.contains("staged_")
                }
                matches?.forEach { f ->
                    pathsToRescan.add(f.absolutePath)
                    if (f.delete()) {
                        deletedFiles++
                    }
                }
            }
        }

        // 5. Picu MediaScanner Android agar galeri (MIUI/HyperOS Gallery) me-refresh instan
        if (pathsToRescan.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                context,
                pathsToRescan.toTypedArray(),
                null,
                null
            )
        }
        if (shell.status().available) {
            try {
                shell.exec("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera", 2000L)
            } catch (t: Throwable) {
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("deleted_mediastore_videos", deletedVideos)
            put("deleted_mediastore_images", deletedImages)
            put("deleted_physical_files", deletedFiles)
            put("message", "Pembersihan selesai: $deletedVideos video MediaStore dan berkas fisik sementara berhasil dibersihkan.")
        }
    }
}
