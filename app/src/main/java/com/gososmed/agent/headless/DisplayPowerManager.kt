package com.gososmed.agent.headless

import android.content.Context
import android.util.Log
import com.gososmed.agent.privileged.PrivilegedShellHolder
import java.io.File
import java.io.FileOutputStream

/**
 * Pengendali daya display fisik tingkat hardware (Display Power Controller).
 * Menggunakan mekanisme identik dengan `scrcpy --turn-screen-off` (mematikan daya panel fisik
 * display via DisplayManagerGlobal.requestDisplayPower / SurfaceControl.setDisplayPowerMode),
 * sehingga:
 *  1. Panel layar fisik di HP padam total (isPoweredOn = 0, 0 Watt, dingin).
 *  2. MediaProjection (Remote Viewer) tetap menerima frame video berwarna penuh 100%.
 *  3. Sentuhan remote dan automasi AI Agent tetap bekerja tanpa membangunkan layar fisik HP.
 *  4. Menekan tombol fisik POWER di HP memulihkan daya layar ke normal (isPoweredOn = 1).
 */
object DisplayPowerManager {

    private const val TAG = "GoAgent"
    private const val TARGET_JAR = "/data/local/tmp/display_power.jar"
    private const val LOCK_FILE = "/data/local/tmp/headless_display.lock"
    fun ensureJarDeployed(context: Context) {
        Thread {
            try {
                val cacheJar = File(context.cacheDir, "display_power.jar")
                context.assets.open("display_power.jar").use { input ->
                    FileOutputStream(cacheJar).use { output ->
                        input.copyTo(output)
                    }
                }
                cacheJar.setReadable(true, false)
                var shell = PrivilegedShellHolder.get()
                var attempts = 0
                while (!shell.status().available && attempts < 10) {
                    Thread.sleep(300L)
                    shell = PrivilegedShellHolder.get()
                    attempts++
                }

                if (shell.status().available) {
                    val cpRes = shell.exec("cp ${cacheJar.absolutePath} $TARGET_JAR")
                    val chmodRes = shell.exec("chmod 644 $TARGET_JAR")
                    Log.i(TAG, "DisplayPowerManager deployed JAR to $TARGET_JAR (cp: ${cpRes.ok}, chmod: ${chmodRes.ok})")
                } else {
                    Log.w(TAG, "DisplayPowerManager ensureJarDeployed: shell not available after wait")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DisplayPowerManager ensureJarDeployed error: ${t.message}", t)
            }
        }.start()
    }

    fun setDisplayPower(on: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        Thread {
            try {
                var shell = PrivilegedShellHolder.get()
                var attempts = 0
                while (!shell.status().available && attempts < 6) {
                    Thread.sleep(250L)
                    shell = PrivilegedShellHolder.get()
                    attempts++
                }

                if (shell.status().available) {
                    if (on) {
                        shell.exec("rm -f $LOCK_FILE")
                        shell.exec("pkill -f com.gososmed.agent.headless.DisplayPower")
                        val res = shell.exec("app_process -Djava.class.path=$TARGET_JAR / com.gososmed.agent.headless.DisplayPower on")
                        Log.i(TAG, "DisplayPowerManager setDisplayPower(on=true) => ok=${res.ok}")
                        onComplete?.invoke(res.ok)
                    } else {
                        shell.exec("rm -f $LOCK_FILE")
                        shell.exec("pkill -f com.gososmed.agent.headless.DisplayPower")
                        Thread.sleep(100L)
                        val res = shell.exec("setsid app_process -Djava.class.path=$TARGET_JAR / com.gososmed.agent.headless.DisplayPower off </dev/null >/data/local/tmp/dp.log 2>&1 &")
                        Log.i(TAG, "DisplayPowerManager setDisplayPower(on=false) => daemon started via setsid, ok=${res.ok}")
                        onComplete?.invoke(res.ok)
                    }
                } else {
                    Log.w(TAG, "DisplayPowerManager setDisplayPower: shell not available (paired=${shell.status().paired})")
                    onComplete?.invoke(false)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DisplayPowerManager setDisplayPower error: ${t.message}")
                onComplete?.invoke(false)
            }
        }.start()
    }
}
