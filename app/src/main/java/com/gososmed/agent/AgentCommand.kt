package com.gososmed.agent

import org.json.JSONObject

/**
 * Command protocol between the GoSosmed agenthub (server) and the agent.
 *
 * We define a small JSON-over-WebSocket protocol so the server-side
 * `AgentHub` can drive the device exactly the way the adb `Device` driver
 * does. Messages are request/response keyed by `id` so the server can map
 * replies to the awaited call.
 *
 * Request  : { "id": 1, "cmd": "dump" | "tap" | "tapByText" | "setText"
 *                    | "back" | "home" | "recents" | "notify" | "package"
 *                    | "ping", ...args }
 * Response : { "id": 1, "ok": true,  "result": {...} }
 *          : { "id": 1, "ok": false, "error": "..." }
 */
object AgentCommand {

    const val CMD_DUMP = "dump"
    const val CMD_TAP = "tap"
    const val CMD_TAP_BY_TEXT = "tapByText"
    const val CMD_TAP_FIRST_CLICKABLE = "tapFirstClickable"
    const val CMD_SET_TEXT = "setText"
    const val CMD_BACK = "back"
    const val CMD_HOME = "home"
    const val CMD_RECENTS = "recents"
    const val CMD_NOTIFY = "notify"
    const val CMD_PACKAGE = "package"
    const val CMD_PING = "ping"
    const val CMD_START_APP = "startApp"
    const val CMD_KILL_APP = "killApp"
    const val CMD_HAS_PACKAGE = "hasPackage"
    const val CMD_LIST_PACKAGES = "listPackages"

    /** Executes one command request and returns the response JSONObject. */
    fun execute(req: JSONObject): JSONObject {
        val id = req.optInt("id", -1)
        val cmd = req.optString("cmd", "")
        val resp = JSONObject()
        resp.put("id", id)

        // Retry: the AccessibilityService's static instance can briefly become
        // null when MIUI calls onDestroy(on startActivity to another app, e.g.
        // XSpace Dual Apps resolver) then quickly re-binds. Wait a short moment
        // for the re-bound instance to come back before giving up.
        var svc = AgentAccessibilityService.instance
        if (svc == null) {
            for (i in 0..9) {
                Thread.sleep(200)
                svc = AgentAccessibilityService.instance
                if (svc != null) break
            }
        }

        if (svc == null) {
            resp.put("ok", false).put("error", "accessibility service not connected/ready")
            return resp
        }
        // Use the non-null svc instance safely inside the block.
        val result = executeWith(svc, cmd, req)
        result.put("id", id)
        return result
    }

    private fun executeWith(svc: AgentAccessibilityService, cmd: String, req: JSONObject): JSONObject {
        val resp = JSONObject()
        when (cmd) {
                CMD_PING -> {
                    resp.put("ok", true)
                    resp.put("result", JSONObject().put("pong", true))
                }
                CMD_DUMP -> {
                    val xml = svc.dumpXml()
                    resp.put("ok", true)
                    resp.put("result", JSONObject().apply {
                        put("xml", xml)
                        put("package", svc.currentPackage())
                    })
                }
                CMD_TAP -> {
                    val x = req.optInt("x", -1)
                    val y = req.optInt("y", -1)
                    if (x < 0 || y < 0) {
                        resp.put("ok", false).put("error", "tap requires x,y")
                    } else {
                        resp.put("ok", true).put("result", JSONObject().put("ok", svc.tap(x, y)))
                    }
                }
                CMD_TAP_BY_TEXT -> {
                    val text = req.optString("text", "")
                    if (text.isEmpty()) {
                        resp.put("ok", false).put("error", "tapByText requires text")
                    } else {
                        resp.put("ok", true).put("result", JSONObject().put("ok", svc.tapByText(text)))
                    }
                }
                CMD_TAP_FIRST_CLICKABLE -> {
                    val b = svc.tapFirstClickable()
                    resp.put("ok", true).put(
                        "result",
                        JSONObject().apply {
                            put("ok", b != null)
                            if (b != null) {
                                put("bounds", "[${b.left},${b.top}][${b.right},${b.bottom}]")
                            }
                        }
                    )
                }
                CMD_SET_TEXT -> {
                    val text = req.optString("text", "")
                    resp.put("ok", true).put("result", JSONObject().put("ok", svc.setText(text)))
                }
                CMD_BACK -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.pressBack()))
                CMD_HOME -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.pressHome()))
                CMD_RECENTS -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.openRecents()))
                CMD_NOTIFY -> resp.put("ok", true).put("result", JSONObject().put("ok", svc.notifyAction()))
                CMD_PACKAGE -> resp.put("ok", true).put("result", JSONObject().put("package", svc.currentPackage()))
                CMD_START_APP -> {
                    val pkg = req.optString("package", "")
                    if (pkg.isEmpty()) {
                        resp.put("ok", false).put("error", "startApp requires package")
                    } else {
                        val activity = req.optString("activity", "")
                        resp.put("ok", true).put(
                            "result",
                            JSONObject().put("ok", svc.startApp(pkg, activity.ifEmpty { null }))
                        )
                    }
                }
                CMD_KILL_APP -> {
                    val pkg = req.optString("package", "")
                    if (pkg.isEmpty()) {
                        resp.put("ok", false).put("error", "killApp requires package")
                    } else {
                        resp.put("ok", true).put("result", JSONObject().put("ok", svc.killApp(pkg)))
                    }
                }
                CMD_HAS_PACKAGE -> {
                    val pkg = req.optString("package", "")
                    resp.put("ok", true).put("result", JSONObject().put("installed", svc.hasPackage(pkg)))
                }
                CMD_LIST_PACKAGES -> {
                    resp.put("ok", true).put("result", JSONObject().put("packages", svc.listPackages()))
                }
                else -> resp.put("ok", false).put("error", "unknown cmd: $cmd")
            }
            return resp
        }
    }
}
