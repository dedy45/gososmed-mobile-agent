package com.gososmed.agent

import org.json.JSONObject

/**
 * Protocol Version 2 envelope, command definitions, and canonical reason codes
 * according to PLAN-DETERMINISTIC-ANDROID-PORTAL.md (Sections 5 & 9).
 */
object ProtocolV2 {
    const val CURRENT_VERSION: Int = 2
    const val LEGACY_VERSION: Int = 1

    object Commands {
        const val OBSERVE = "observe"
        const val RESOLVE = "resolve"
        const val ACT_NODE = "actNode"
        const val ACT_AND_VERIFY = "actAndVerify"
        const val WAIT_FOR_NODE = "waitForNode"
        const val WAIT_FOR_SCREEN = "waitForScreen"
        const val CANCEL_ACTION = "cancelAction"
        const val START_DEBUG_FRAMES = "startDebugFrames"
        const val STOP_DEBUG_FRAMES = "stopDebugFrames"

        val ALL: Set<String> = setOf(
            OBSERVE,
            RESOLVE,
            ACT_NODE,
            ACT_AND_VERIFY,
            WAIT_FOR_NODE,
            WAIT_FOR_SCREEN,
            CANCEL_ACTION,
            START_DEBUG_FRAMES,
            STOP_DEBUG_FRAMES
        )
    }

    object ReasonCodes {
        const val NO_NODE = "no_node"
        const val AMBIGUOUS = "ambiguous"
        const val INVALID_BOUNDS = "invalid_bounds"
        const val STALE_SNAPSHOT = "stale_snapshot"
        const val WRONG_PACKAGE = "wrong_package"
        const val UNSTABLE_TREE = "unstable_tree"
        const val ACTION_NOT_SUPPORTED = "action_not_supported"
        const val ACTION_REJECTED = "action_rejected"
        const val GESTURE_CANCELLED = "gesture_cancelled"
        const val SCREEN_NOT_CHANGED = "screen_not_changed"
        const val UNEXPECTED_SCREEN = "unexpected_screen"
        const val BLOCKED_DIALOG = "blocked_dialog"
        const val TIMEOUT = "timeout"
        const val CANCELLED = "cancelled"
        const val DEVICE_BUSY = "device_busy"
        const val SUBMIT_BARRIER = "submit_barrier"
        const val SCREENSHOT_FAILED = "screenshot_failed"
        const val PROTOCOL_MISMATCH = "protocol_mismatch"

        val ALL: Set<String> = setOf(
            NO_NODE,
            AMBIGUOUS,
            INVALID_BOUNDS,
            STALE_SNAPSHOT,
            WRONG_PACKAGE,
            UNSTABLE_TREE,
            ACTION_NOT_SUPPORTED,
            ACTION_REJECTED,
            GESTURE_CANCELLED,
            SCREEN_NOT_CHANGED,
            UNEXPECTED_SCREEN,
            BLOCKED_DIALOG,
            TIMEOUT,
            CANCELLED,
            DEVICE_BUSY,
            SUBMIT_BARRIER,
            SCREENSHOT_FAILED,
            PROTOCOL_MISMATCH
        )
    }

    data class Timing(
        val startedAtMs: Long = 0L,
        val completedAtMs: Long = 0L,
        val durationMs: Long = (completedAtMs - startedAtMs).coerceAtLeast(0L)
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("started_at_ms", startedAtMs)
            put("completed_at_ms", completedAtMs)
            put("duration_ms", durationMs)
        }

        companion object {
            fun fromJson(json: JSONObject?): Timing {
                if (json == null) return Timing()
                val started = json.optLong("started_at_ms", 0L)
                val completed = json.optLong("completed_at_ms", 0L)
                val duration = json.optLong("duration_ms", (completed - started).coerceAtLeast(0L))
                return Timing(
                    startedAtMs = started,
                    completedAtMs = completed,
                    durationMs = duration
                )
            }

            fun create(startedAtMs: Long, completedAtMs: Long = System.currentTimeMillis()): Timing {
                return Timing(
                    startedAtMs = startedAtMs,
                    completedAtMs = completedAtMs,
                    durationMs = (completedAtMs - startedAtMs).coerceAtLeast(0L)
                )
            }
        }
    }

    data class Request(
        val protocolVersion: Int,
        val requestId: String,
        val cmd: String,
        val deadlineMs: Long = 10000L,
        val args: JSONObject = JSONObject()
    ) {
        init {
            if (protocolVersion == CURRENT_VERSION) {
                require(requestId.isNotBlank()) { "request_id is mandatory and cannot be blank in Protocol v2" }
            }
        }

        val effectiveDeadlineMs: Long
            get() = if (deadlineMs <= 0L) 10000L else deadlineMs

        fun toJson(): JSONObject = JSONObject().apply {
            put("protocol_version", protocolVersion)
            put("request_id", requestId)
            put("cmd", cmd)
            put("deadline_ms", deadlineMs)
            put("args", args)
        }

        companion object {
            fun isLegacyV1(json: JSONObject): Boolean {
                return !json.has("protocol_version") || json.optInt("protocol_version", 0) == LEGACY_VERSION
            }

            fun fromJson(json: JSONObject): Request {
                val version = json.optInt("protocol_version", LEGACY_VERSION)
                val requestId = json.optString("request_id", "")
                val cmd = json.optString("cmd", json.optString("type", ""))
                val rawDeadline = json.optLong("deadline_ms", 10000L)
                val deadlineMs = if (rawDeadline <= 0L) 10000L else rawDeadline
                val args = when {
                    json.has("args") -> json.optJSONObject("args") ?: JSONObject()
                    json.has("payload") -> json.optJSONObject("payload") ?: JSONObject()
                    else -> JSONObject()
                }

                return Request(
                    protocolVersion = version,
                    requestId = requestId,
                    cmd = cmd,
                    deadlineMs = deadlineMs,
                    args = args
                )
            }
        }
    }

    data class Response(
        val protocolVersion: Int = CURRENT_VERSION,
        val requestId: String,
        val ok: Boolean,
        val reasonCode: String? = null,
        val retryable: Boolean = false,
        val result: JSONObject = JSONObject(),
        val timing: Timing = Timing()
    ) {
        init {
            if (!ok && reasonCode == null) {
                throw IllegalArgumentException("reason_code is mandatory when ok == false")
            }
            if (ok && reasonCode != null) {
                throw IllegalArgumentException("reason_code must be null when ok == true")
            }
            if (result.has("ok") && result.optBoolean("ok") != ok) {
                throw IllegalArgumentException("Nested result.ok cannot contradict top-level ok")
            }
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("protocol_version", protocolVersion)
            put("request_id", requestId)
            put("ok", ok)
            if (reasonCode != null) {
                put("reason_code", reasonCode)
            }
            put("retryable", retryable)
            put("result", result)
            put("timing", timing.toJson())
        }

        companion object {
            fun success(
                requestId: String,
                result: JSONObject = JSONObject(),
                timing: Timing = Timing(),
                protocolVersion: Int = CURRENT_VERSION
            ): Response {
                return Response(
                    protocolVersion = protocolVersion,
                    requestId = requestId,
                    ok = true,
                    reasonCode = null,
                    retryable = false,
                    result = result,
                    timing = timing
                )
            }

            fun error(
                requestId: String,
                reasonCode: String,
                retryable: Boolean = false,
                result: JSONObject = JSONObject(),
                timing: Timing = Timing(),
                protocolVersion: Int = CURRENT_VERSION
            ): Response {
                return Response(
                    protocolVersion = protocolVersion,
                    requestId = requestId,
                    ok = false,
                    reasonCode = reasonCode,
                    retryable = retryable,
                    result = result,
                    timing = timing
                )
            }

            fun protocolMismatch(
                requestId: String,
                unsupportedVersion: Int,
                timing: Timing = Timing()
            ): Response {
                val detail = JSONObject().apply {
                    put("supported_versions", listOf(LEGACY_VERSION, CURRENT_VERSION))
                    put("received_version", unsupportedVersion)
                }
                return Response(
                    protocolVersion = CURRENT_VERSION,
                    requestId = requestId,
                    ok = false,
                    reasonCode = ReasonCodes.PROTOCOL_MISMATCH,
                    retryable = false,
                    result = detail,
                    timing = timing
                )
            }

            fun fromJson(json: JSONObject): Response {
                val version = json.optInt("protocol_version", CURRENT_VERSION)
                val requestId = json.optString("request_id", "")
                val ok = json.getBoolean("ok")
                val reasonCode = if (json.has("reason_code")) json.getString("reason_code") else null
                val retryable = json.optBoolean("retryable", false)
                val result = json.optJSONObject("result") ?: JSONObject()
                val timing = Timing.fromJson(json.optJSONObject("timing"))

                return Response(
                    protocolVersion = version,
                    requestId = requestId,
                    ok = ok,
                    reasonCode = reasonCode,
                    retryable = retryable,
                    result = result,
                    timing = timing
                )
            }
        }
    }

    fun isSupportedVersion(version: Int): Boolean {
        return version in LEGACY_VERSION..CURRENT_VERSION
    }

    fun validate(json: JSONObject): Response? {
        val version = json.optInt("protocol_version", LEGACY_VERSION)
        if (version > CURRENT_VERSION || version < LEGACY_VERSION) {
            return Response.protocolMismatch(json.optString("request_id", ""), version)
        }
        if (version == CURRENT_VERSION) {
            val reqId = json.optString("request_id", "")
            if (reqId.isBlank()) {
                return Response.error(
                    requestId = "",
                    reasonCode = ReasonCodes.ACTION_REJECTED,
                    result = JSONObject().apply { put("error", "missing_or_blank_request_id") }
                )
            }
        }
        return null
    }
}
