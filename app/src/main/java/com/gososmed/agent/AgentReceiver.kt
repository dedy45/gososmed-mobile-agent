package com.gososmed.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Result is written to <external-files>/agent_result.txt for `adb pull`.
 */
class AgentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val req = JSONObject().put("cmd", cmd)
        intent.getStringExtra("text")?.let { req.put("text", it) }
        if (intent.hasExtra("x")) req.put("x", intent.getIntExtra("x", -1))
        if (intent.hasExtra("y")) req.put("y", intent.getIntExtra("y", -1))

        val resp = AgentCommand.execute(req)
        // Blocking-ish: onReceive has ~10s; dump of a big window can take a
        // moment but usually fits. We goAsync to be safe.
        val pending = goAsync()
        Thread {
            try {
                writeResult(context, cmd, resp.toString())
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun writeResult(context: Context, tag: String, value: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            java.io.File(dir, "agent_result.txt").writeText("$tag => $value\n")
        } catch (_: Exception) {
            // no storage; log-only
        }
    }
}
