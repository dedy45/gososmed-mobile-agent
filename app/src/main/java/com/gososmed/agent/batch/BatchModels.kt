package com.gososmed.agent.batch

import com.gososmed.agent.ProtocolV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * Batasan aksi yang diizinkan dalam Batch Flow Execution.
 * Menegakkan pagar keamanan (Whitelisted Actions Only - §3):
 * Seluruh aksi di dalam batch dibatasi pada primitif UI dan navigasi aman.
 */
object BatchSecurityGuard {
    val ALLOWED_ACTIONS: Set<String> = setOf(
        "startApp",
        "waitForScreen",
        "waitForNode",
        "actNode",
        "actAndVerify",
        "scrollUntilVisible",
        "tap",
        "click",
        "inputText",
        "setText",
        "replaceText",
        "pressKey",
        "back",
        "home",
        "recents",
        "sleep"
    )

    fun isActionAllowed(action: String): Boolean = action in ALLOWED_ACTIONS
}

/**
 * Representasi satu langkah dalam sekuens batch.
 */
data class BatchStep(
    val stepId: String,
    val action: String,
    val params: JSONObject = JSONObject(),
    val timeoutMs: Long = 10_000L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("step_id", stepId)
        put("action", action)
        put("params", params)
        put("timeout_ms", timeoutMs)
    }

    companion object {
        fun fromJson(json: JSONObject, defaultIndex: Int = 1): BatchStep {
            val stepId = json.optString("step_id", json.optString("id", "step_$defaultIndex"))
            val action = json.optString("action", json.optString("cmd", ""))
            val params = when {
                json.has("params") -> json.optJSONObject("params") ?: JSONObject()
                json.has("args") -> json.optJSONObject("args") ?: JSONObject()
                else -> JSONObject()
            }
            val timeoutMs = json.optLong("timeout_ms", 10_000L).coerceIn(500L, 60_000L)
            return BatchStep(
                stepId = stepId,
                action = action,
                params = params,
                timeoutMs = timeoutMs
            )
        }
    }
}

/**
 * Permintaan eksekusi batch dari server atau penguji.
 */
data class BatchRequest(
    val flowId: String,
    val timeoutTotalMs: Long = 30_000L,
    val steps: List<BatchStep> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("flow_id", flowId)
        put("timeout_total_ms", timeoutTotalMs)
        val arr = JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        put("steps", arr)
    }

    companion object {
        fun fromJson(args: JSONObject, rootJson: JSONObject = JSONObject()): BatchRequest {
            val flowId = args.optString(
                "flow_id",
                rootJson.optString("flow_id", "flow_${System.currentTimeMillis()}")
            )
            val rawTimeout = args.optLong(
                "timeout_total_ms",
                rootJson.optLong("deadline_ms", rootJson.optLong("timeout_total_ms", 30_000L))
            )
            val timeoutTotalMs = rawTimeout.coerceIn(1_000L, 120_000L)

            val rawSteps = when {
                args.has("steps") -> args.optJSONArray("steps")
                rootJson.has("steps") -> rootJson.optJSONArray("steps")
                else -> null
            }

            val stepList = mutableListOf<BatchStep>()
            if (rawSteps != null) {
                for (i in 0 until rawSteps.length()) {
                    val stepObj = rawSteps.optJSONObject(i) ?: continue
                    stepList.add(BatchStep.fromJson(stepObj, defaultIndex = i + 1))
                }
            }

            return BatchRequest(
                flowId = flowId,
                timeoutTotalMs = timeoutTotalMs,
                steps = stepList
            )
        }
    }
}

/**
 * Snapshot telemetri perangkat pada suatu langkah eksekusi.
 */
data class DeviceTelemetry(
    val activePackage: String? = null,
    val activeActivity: String? = null,
    val elementsCount: Int = 0,
    val elementsHash: String? = null,
    val topSemanticTexts: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("active_package", activePackage ?: JSONObject.NULL)
        put("active_activity", activeActivity ?: JSONObject.NULL)
        put("elements_count", elementsCount)
        put("elements_hash", elementsHash ?: JSONObject.NULL)
        put("top_semantic_texts", JSONArray(topSemanticTexts))
    }
}

/**
 * Laporan telemetri terstruktur per langkah (STEP_TRAJECTORY).
 */
data class StepTrajectoryReport(
    val event: String = ProtocolV2.Events.STEP_TRAJECTORY,
    val flowId: String,
    val stepId: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val status: String, // "COMPLETED" | "FAILED" | "ABORTED"
    val durationMs: Long,
    val deviceTelemetry: DeviceTelemetry? = null,
    val failureDetail: JSONObject? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "step_trajectory")
        put("event", event)
        put("protocol_version", ProtocolV2.CURRENT_VERSION)
        put("flow_id", flowId)
        put("step_id", stepId)
        put("step_index", stepIndex)
        put("total_steps", totalSteps)
        put("status", status)
        put("duration_ms", durationMs)
        put("device_telemetry", deviceTelemetry?.toJson() ?: JSONObject.NULL)
        put("failure_detail", failureDetail ?: JSONObject.NULL)
    }
}

/**
 * Hasil akhir eksekusi seluruh batch.
 */
data class BatchResult(
    val flowId: String,
    val status: String, // "COMPLETED" | "FAILED" | "CANCELLED" | "TIMEOUT"
    val stepsCompleted: Int,
    val totalSteps: Int,
    val failedStepId: String? = null,
    val reasonCode: String? = null,
    val trajectories: List<StepTrajectoryReport> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("flow_id", flowId)
        put("status", status)
        put("steps_completed", stepsCompleted)
        put("total_steps", totalSteps)
        if (failedStepId != null) put("failed_step_id", failedStepId)
        if (reasonCode != null) put("reason_code", reasonCode)
        val arr = JSONArray()
        trajectories.forEach { arr.put(it.toJson()) }
        put("trajectories", arr)
    }
}
