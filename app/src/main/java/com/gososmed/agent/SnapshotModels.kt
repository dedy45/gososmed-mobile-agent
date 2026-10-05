package com.gososmed.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Foundational domain models for snapshot contract (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Sections 6 & 7).
 * Pure Kotlin/primitive wrappers independent of android.graphics.Rect / AccessibilityNodeInfo
 * to support 100% JVM unit testability.
 */

data class SnapshotDisplay(
    val id: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val densityDpi: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("width", width)
        put("height", height)
        put("rotation", rotation)
        put("density_dpi", densityDpi)
    }

    companion object {
        fun fromJson(json: JSONObject): SnapshotDisplay {
            return SnapshotDisplay(
                id = json.getInt("id"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                rotation = json.getInt("rotation"),
                densityDpi = json.getInt("density_dpi")
            )
        }
    }
}

data class SnapshotForeground(
    val packageName: String,
    val windowId: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("package", packageName)
        put("window_id", windowId)
    }

    companion object {
        fun fromJson(json: JSONObject): SnapshotForeground {
            return SnapshotForeground(
                packageName = json.getString("package"),
                windowId = json.getInt("window_id")
            )
        }
    }
}

data class SnapshotQuality(
    val valid: Boolean,
    val invalidBoundsRatio: Double,
    val windowChanged: Boolean,
    val reasons: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("valid", valid)
        put("invalid_bounds_ratio", invalidBoundsRatio)
        put("window_changed", windowChanged)
        if (reasons.isNotEmpty()) {
            put("reasons", JSONArray(reasons))
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SnapshotQuality {
            val reasonsList = mutableListOf<String>()
            val reasonsArr = json.optJSONArray("reasons")
            if (reasonsArr != null) {
                for (i in 0 until reasonsArr.length()) {
                    reasonsList.add(reasonsArr.getString(i))
                }
            }
            return SnapshotQuality(
                valid = json.getBoolean("valid"),
                invalidBoundsRatio = json.getDouble("invalid_bounds_ratio"),
                windowChanged = json.getBoolean("window_changed"),
                reasons = reasonsList
            )
        }
    }
}

data class SnapshotImage(
    val format: String,
    val width: Int,
    val height: Int,
    val quality: Int,
    val binaryMessageId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", format)
        put("width", width)
        put("height", height)
        put("quality", quality)
        if (binaryMessageId != null) {
            put("binary_message_id", binaryMessageId)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SnapshotImage {
            return SnapshotImage(
                format = json.getString("format"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                quality = json.getInt("quality"),
                binaryMessageId = if (json.has("binary_message_id")) json.getString("binary_message_id") else null
            )
        }
    }
}

data class SnapshotBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val isNegative: Boolean get() = left < 0 || top < 0 || right < left || bottom < top
    val area: Long get() = if (isEmpty) 0L else width.toLong() * height.toLong()

    fun contains(x: Int, y: Int): Boolean {
        return x in left until right && y in top until bottom
    }

    fun contains(other: SnapshotBounds): Boolean {
        return !isEmpty && !other.isEmpty &&
                left <= other.left && top <= other.top &&
                right >= other.right && bottom >= other.bottom
    }

    fun intersects(other: SnapshotBounds): Boolean {
        return !isEmpty && !other.isEmpty &&
                left < other.right && right > other.left &&
                top < other.bottom && bottom > other.top
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
    }

    companion object {
        val ZERO = SnapshotBounds(0, 0, 0, 0)

        fun fromJson(json: JSONObject): SnapshotBounds {
            return SnapshotBounds(
                left = json.getInt("left"),
                top = json.getInt("top"),
                right = json.getInt("right"),
                bottom = json.getInt("bottom")
            )
        }
    }
}

data class SnapshotNode(
    val nodeId: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: SnapshotBounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val password: Boolean = false,
    val ancestorPath: String = "",
    val siblingIndex: Int = 0,
    val childCount: Int = 0,
    val actionable: Boolean = false,
    val clickableAncestorId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("node_id", nodeId)
        put("window_id", windowId)
        put("package_name", packageName)
        put("class_name", className)
        if (resourceId != null) put("resource_id", resourceId)
        if (text != null) put("text", text)
        if (contentDescription != null) put("content_description", contentDescription)
        put("bounds", bounds.toJson())
        put("clickable", clickable)
        put("long_clickable", longClickable)
        put("editable", editable)
        put("checkable", checkable)
        put("checked", checked)
        put("scrollable", scrollable)
        put("focusable", focusable)
        put("focused", focused)
        put("enabled", enabled)
        put("selected", selected)
        put("password", password)
        put("ancestor_path", ancestorPath)
        put("sibling_index", siblingIndex)
        put("child_count", childCount)
        put("actionable", actionable)
        if (clickableAncestorId != null) put("clickable_ancestor_id", clickableAncestorId)
    }

    companion object {
        fun fromJson(json: JSONObject): SnapshotNode {
            return SnapshotNode(
                nodeId = json.getString("node_id"),
                windowId = json.getInt("window_id"),
                packageName = json.getString("package_name"),
                className = json.getString("class_name"),
                resourceId = if (json.has("resource_id")) json.getString("resource_id") else null,
                text = if (json.has("text")) json.getString("text") else null,
                contentDescription = if (json.has("content_description")) json.getString("content_description") else null,
                bounds = SnapshotBounds.fromJson(json.getJSONObject("bounds")),
                clickable = json.optBoolean("clickable", false),
                longClickable = json.optBoolean("long_clickable", false),
                editable = json.optBoolean("editable", false),
                checkable = json.optBoolean("checkable", false),
                checked = json.optBoolean("checked", false),
                scrollable = json.optBoolean("scrollable", false),
                focusable = json.optBoolean("focusable", false),
                focused = json.optBoolean("focused", false),
                enabled = json.optBoolean("enabled", true),
                selected = json.optBoolean("selected", false),
                password = json.optBoolean("password", false),
                ancestorPath = json.optString("ancestor_path", ""),
                siblingIndex = json.optInt("sibling_index", 0),
                childCount = json.optInt("child_count", 0),
                actionable = json.optBoolean("actionable", false),
                clickableAncestorId = if (json.has("clickable_ancestor_id")) json.getString("clickable_ancestor_id") else null
            )
        }
    }
}

data class SnapshotResult(
    val snapshotId: String,
    val frameSeq: Long,
    val capturedAtMs: Long,
    val display: SnapshotDisplay,
    val foreground: SnapshotForeground,
    val treeHash: String,
    val screenFingerprint: String,
    val quality: SnapshotQuality,
    val nodes: List<SnapshotNode>,
    val image: SnapshotImage? = null,
    val truncated: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("snapshot_id", snapshotId)
        put("frame_seq", frameSeq)
        put("captured_at_ms", capturedAtMs)
        put("display", display.toJson())
        put("foreground", foreground.toJson())
        put("tree_hash", treeHash)
        put("screen_fingerprint", screenFingerprint)
        put("quality", quality.toJson())

        val nodesArr = JSONArray()
        for (node in nodes) {
            nodesArr.put(node.toJson())
        }
        put("nodes", nodesArr)

        if (image != null) {
            put("image", image.toJson())
        }
        put("truncated", truncated)
    }

    companion object {
        fun fromJson(json: JSONObject): SnapshotResult {
            val display = SnapshotDisplay.fromJson(json.getJSONObject("display"))
            val foreground = SnapshotForeground.fromJson(json.getJSONObject("foreground"))
            val quality = SnapshotQuality.fromJson(json.getJSONObject("quality"))

            val nodesList = mutableListOf<SnapshotNode>()
            val nodesArr = json.optJSONArray("nodes")
            if (nodesArr != null) {
                for (i in 0 until nodesArr.length()) {
                    nodesList.add(SnapshotNode.fromJson(nodesArr.getJSONObject(i)))
                }
            }

            val image = if (json.has("image")) SnapshotImage.fromJson(json.getJSONObject("image")) else null

            return SnapshotResult(
                snapshotId = json.getString("snapshot_id"),
                frameSeq = json.getLong("frame_seq"),
                capturedAtMs = json.getLong("captured_at_ms"),
                display = display,
                foreground = foreground,
                treeHash = json.getString("tree_hash"),
                screenFingerprint = json.getString("screen_fingerprint"),
                quality = quality,
                nodes = nodesList,
                image = image,
                truncated = json.optBoolean("truncated", false)
            )
        }
    }
}
