package com.gososmed.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Allows driving the agent from `adb shell am broadcast` (validation / P0)
 * without MainActivity being in the foreground. Because the Accessibility
 * service keeps reading the ACTIVE window, a `cmd=dump` fired while Facebook
 * or TikTok is in the foreground dumps THAT app's window — real evidence that
 * the agent reads third-party app UIs, not just its own.
 *
 * Usage (from adb):
 *   adb shell am broadcast -a com.gososmed.agent.CMD \
 *     --es cmd dump
 *   adb shell am broadcast -a com.gososmed.agent.CMD \
 *     --es cmd tapByText --es text "Log in"
 *   adb shell am broadcast -a com.gososmed.agent.CMD \
 *     --es cmd setText --es text "user@example.com"
 *
 * Results are written to the app INTERNAL files dir (`run-as` readable) and
 * echoed to logcat (tag "GoAgent") so validation never depends on storage
 * permissions.
 */
class AgentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val req = JSONObject().put("cmd", cmd)
        intent.getStringExtra("text")?.let { req.put("text", it) }
        intent.getStringExtra("package")?.let { req.put("package", it) }
        if (intent.hasExtra("x")) req.put("x", intent.getIntExtra("x", -1))
        if (intent.hasExtra("y")) req.put("y", intent.getIntExtra("y", -1))

        // onReceive is limited to ~10s; run the (possibly slow) dump on a
        // background thread and finish() via goAsync. The service may still
        // be binding, so wait until it is ready (up to ~3s).
        val pending = goAsync()
        Thread {
            try {
                var ready = AgentAccessibilityService.instance?.isServiceReady() == true
                var attempts = 0
                while (!ready && attempts < 6) {
                    Thread.sleep(500)
                    attempts++
                    ready = AgentAccessibilityService.instance?.isServiceReady() == true
                }
                val resp = AgentCommand.execute(req)
                ResultStore.write(context, cmd, resp.toString())
            } finally {
                pending.finish()
            }
        }.start()
    }

    object ResultStore {
        private const val TAG = "GoAgent"

        fun write(context: Context, tag: String, value: String) {
            Log.i(TAG, "RESULT[$tag] => $value")
            try {
                val dir = context.getFilesDir()
                val f = java.io.File(dir, "agent_result.txt")
                f.writeText("$tag => $value\n")
                Log.i(TAG, "written to ${f.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "write failed: ${e.message}")
            }
            // Also mirror to external files dir (adb-pull friendly) when it
            // exists; failures here are non-fatal.
            try {
                val ext = context.getExternalFilesDir(null)
                if (ext != null) {
                    java.io.File(ext, "agent_result.txt").writeText("$tag => $value\n")
                }
            } catch (_: Exception) {
                // non-fatal
            }
        }
    }
}
