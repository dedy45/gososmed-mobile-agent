package com.gososmed.agent

import org.json.JSONObject

/**
 * Selector query specification for resolving nodes in a snapshot (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 8).
 */
data class SelectorQuery(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val nodeId: String? = null,
    val xpath: String? = null,
    val point: Pair<Int, Int>? = null, // Raw coordinate fallback: Pair(x, y)
    val relativeZone: String? = null, // e.g. "top_half", "bottom_half", "center"
    val ocrHint: String? = null,
    val packageName: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (resourceId != null) put("resource_id", resourceId)
        if (text != null) put("text", text)
        if (contentDescription != null) put("content_description", contentDescription)
        if (className != null) put("class_name", className)
        if (nodeId != null) put("node_id", nodeId)
        if (xpath != null) put("xpath", xpath)
        if (point != null) {
            put("point", JSONObject().apply {
                put("x", point.first)
                put("y", point.second)
            })
        }
        if (relativeZone != null) put("relative_zone", relativeZone)
        if (ocrHint != null) put("ocr_hint", ocrHint)
        if (packageName != null) put("package_name", packageName)
    }

    companion object {
        fun fromJson(json: JSONObject): SelectorQuery {
            val pointObj = json.optJSONObject("point")
            val pointPair = if (pointObj != null && pointObj.has("x") && pointObj.has("y")) {
                Pair(pointObj.getInt("x"), pointObj.getInt("y"))
            } else null

            return SelectorQuery(
                resourceId = if (json.has("resource_id")) json.getString("resource_id") else null,
                text = if (json.has("text")) json.getString("text") else null,
                contentDescription = if (json.has("content_description")) json.getString("content_description") else null,
                className = if (json.has("class_name")) json.getString("class_name") else null,
                nodeId = if (json.has("node_id")) json.getString("node_id") else null,
                xpath = if (json.has("xpath")) json.getString("xpath") else null,
                point = pointPair,
                relativeZone = if (json.has("relative_zone")) json.getString("relative_zone") else null,
                ocrHint = if (json.has("ocr_hint")) json.getString("ocr_hint") else null,
                packageName = if (json.has("package_name")) json.getString("package_name")
                    else if (json.has("package")) json.getString("package")
                    else null
            )
        }
    }
}
