package com.gososmed.agent.privileged

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * v0.9.0 — menyimpan identitas kriptografis agent untuk pairing ADB.
 *
 * KENAPA BUTUH KUNCI:
 * `adbd` mengautentikasi klien dengan pasangan kunci RSA + sertifikat. Kunci
 * yang SAMA harus dipakai setiap kali, kalau tidak `adbd` akan meminta pairing
 * ulang setiap koneksi. Jadi kunci digenerate sekali lalu disimpan di
 * penyimpanan privat aplikasi (`filesDir/adb/`).
 *
 * KEAMANAN:
 *  - Berkas disimpan di `filesDir` aplikasi → tidak bisa dibaca aplikasi lain
 *    (kecuali di perangkat root, yang memang di luar model ancaman kita).
 *  - Sertifikat SELF-SIGNED dan hanya dipakai untuk identifikasi ke `adbd`
 *    lokal; ia bukan sertifikat publik dan tidak divalidasi rantai.
 *  - Tidak ada kunci privat yang pernah dikirim keluar HP.
 *
 * LIBRARY: BouncyCastle (`bcpkix`). Dipilih daripada `sun-security-android`
 * (yang dipakai contoh resmi libadb) karena pilihan itu memerlukan
 * `hiddenapibypass` untuk menembus API tersembunyi Android — trik yang rapuh
 * dan bisa patah di Android berikutnya.
 *
 * PENTING — JANGAN PANGGIL DI MAIN THREAD: generate RSA 2048 + tanda tangan
 * sertifikat bisa memakan ratusan milidetik sampai ~2 detik. Pemanggil wajib
 * menjalankannya di worker thread (lihat [AdbPairingController]).
 */
internal object AdbKeyStore {

    private const val TAG = "GoAgentAdbKeys"
    private const val DIR_NAME = "adb"
    private const val KEY_FILE = "private.pk8"
    private const val CERT_FILE = "cert.der"

    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val SIGN_ALGORITHM = "SHA256withRSA"

    /** CN sengaja menyebut app kita agar terlihat wajar di dialog pairing ADB. */
    private const val CERT_CN = "CN=GoSosmed Agent"

    /** 10 tahun — kunci ADB tidak untuk rotasi berkala, hanya identitas. */
    private const val VALIDITY_MS = 10L * 365 * 24 * 3600 * 1000

    /**
     * Kenapa dimundurkan 1 hari: jam HP bisa sedikit berbeda dari jam saat
     * sertifikat dibuat (mis. HP baru boot dan NTP belum sinkron). Tanpa
     * toleransi ini, sertifikat bisa tampak "belum berlaku" dan ditolak.
     */
    private const val CLOCK_SKEW_MS = 24L * 3600 * 1000

    data class KeyMaterial(val privateKey: PrivateKey, val certificate: Certificate)

    /**
     * Muat kunci tersimpan; bila belum ada atau rusak, generate yang baru.
     *
     * Selalu mengembalikan nilai atau melempar exception — tidak pernah
     * mengembalikan kunci setengah jadi.
     */
    @Synchronized
    fun loadOrCreate(context: Context): KeyMaterial {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)

        if (keyFile.isFile && certFile.isFile) {
            try {
                val material = KeyMaterial(readPrivateKey(keyFile), readCertificate(certFile))
                Log.i(TAG, "kunci ADB dimuat dari penyimpanan")
                return material
            } catch (t: Throwable) {
                // Kunci rusak/tidak bisa dibaca: jangan gagalkan agent. Generate
                // ulang adalah satu-satunya jalan, dan konsekuensinya hanya
                // pairing ulang di sisi adbd — bukan kerusakan permanen.
                Log.w(TAG, "kunci ADB tersimpan tidak bisa dibaca, generate ulang", t)
                keyFile.delete()
                certFile.delete()
            }
        }

        val generated = generate()
        writePrivateKey(keyFile, generated.privateKey)
        writeCertificate(certFile, generated.certificate)
        Log.i(TAG, "kunci ADB baru digenerate dan disimpan")
        return generated
    }

    /** Hapus identitas tersimpan — dipakai saat "Putuskan sepenuhnya". */
    @Synchronized
    fun clear(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        File(dir, KEY_FILE).delete()
        File(dir, CERT_FILE).delete()
        Log.i(TAG, "kunci ADB dihapus")
    }

    private fun generate(): KeyMaterial {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(KEY_SIZE)
        val pair = generator.generateKeyPair()

        val now = Date()
        val notBefore = Date(now.time - CLOCK_SKEW_MS)
        val notAfter = Date(now.time + VALIDITY_MS)
        val subject = X500Name(CERT_CN)
        // Serial dari waktu: cukup unik untuk sertifikat self-signed lokal.
        val serial = BigInteger.valueOf(now.time)

        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, pair.public
        )
        val signer = JcaContentSignerBuilder(SIGN_ALGORITHM).build(pair.private)
        val holder = builder.build(signer)
        // Tanpa provider eksplisit: memakai CertificateFactory bawaan Android,
        // jadi kita TIDAK perlu mendaftarkan BouncyCastle sebagai JCA provider
        // (menghindari bentrok dengan BouncyCastle bawaan Android).
        val certificate = JcaX509CertificateConverter().getCertificate(holder)

        return KeyMaterial(pair.private, certificate)
    }

    private fun readPrivateKey(file: File): PrivateKey {
        val bytes = file.readBytes()
        val spec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(spec)
    }

    private fun readCertificate(file: File): Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return file.inputStream().use { factory.generateCertificate(it) }
    }

    private fun writePrivateKey(file: File, key: PrivateKey) {
        // PKCS#8 = format standar `PrivateKey.getEncoded()`.
        file.writeBytes(key.encoded)
    }

    private fun writeCertificate(file: File, certificate: Certificate) {
        // DER biner (bukan PEM): lebih kecil dan tidak perlu encoder Base64
        // yang di Android berada di paket `android.util` (terikat framework).
        file.writeBytes(certificate.encoded)
    }
}
