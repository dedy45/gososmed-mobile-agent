package com.gososmed.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the active window's AccessibilityNodeInfo tree into the SAME
 * `<hierarchy><node .../></hierarchy>` XML shape that
 * `internal/mobile/hierarchy.go` (`ParseHierarchy`, `Node`) reads.
 *
 * The GoSosmed mobile driver expects these attributes on each `<node>`:
 *   text, content-desc, resource-id, class, package, bounds="[x1,y1][x2,y2]",
 *   clickable, checked, selected, enabled, scrollable, index
 * plus nested `<node>` children. We reproduce exactly that so the agent's
 * dump can be fed straight into `ParseHierarchy` with no adapter changes.
 */
object HierarchySerializer {

    // Limit flattening depth to keep the dump bounded on heavy screens.
    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 2000

    fun dump(root: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        sb.append("<hierarchy rotation=\"0\">\n")
        if (root != null) {
            var count = 0
            appendNode(root, sb, 0, 0, MAX_DEPTH) { count++ }
        }
        sb.append("</hierarchy>\n")
        return sb.toString()
    }

    /** Convenience: parse a dump string into a JSON tree for the command layer. */
    fun dumpToJson(root: AccessibilityNodeInfo?): String {
        val arr = JSONArray()
        if (root != null) {
            var count = 0
            appendNodeJson(root, arr, 0, MAX_DEPTH) { count++ }
        }
        return arr.toString()
    }

    // ---- XML path ----

    private fun appendNode(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        index: Int,
        maxDepth: Int,
        counter: () -> Unit
    ) {
        counter()
        if (depth > maxDepth) return
        val b = Rect()
        node.getBoundsInScreen(b)
        sb.append("  ".repeat(depth))
        sb.append("<node index=\"").append(index).append("\"")
        sb.append(" text=\"").append(escape(node.text?.toString() ?: "")).append("\"")
        sb.append(" resource-id=\"").append(escape(node.viewIdResourceName ?: "")).append("\"")
        sb.append(" class=\"").append(escape(node.className?.toString() ?: "")).append("\"")
        sb.append(" package=\"").append(escape(node.packageName?.toString() ?: "")).append("\"")
        sb.append(" content-desc=\"").append(escape(node.contentDescription?.toString() ?: "")).append("\"")
        sb.append(" checkable=\"").append(node.isCheckable).append("\"")
        sb.append(" checked=\"").append(node.isChecked).append("\"")
        sb.append(" clickable=\"").append(node.isClickable).append("\"")
        sb.append(" enabled=\"").append(node.isEnabled).append("\"")
        sb.append(" focusable=\"").append(node.isFocusable).append("\"")
        sb.append(" focused=\"").append(node.isFocused).append("\"")
        sb.append(" scrollable=\"").append(node.isScrollable).append("\"")
        sb.append(" long-clickable=\"").append(node.isLongClickable).append("\"")
        sb.append(" password=\"").append(node.isPassword).append("\"")
        sb.append(" selected=\"").append(node.isSelected).append("\"")
        sb.append(" bounds=\"[").append(b.left).append(",").append(b.top)
        sb.append("][").append(b.right).append(",").append(b.bottom).append("]\"")
        sb.append(">\n")
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            appendNode(child, sb, depth + 1, i, maxDepth, counter)
        }
        sb.append("  ".repeat(depth)).append("</node>\n")
    }

    // ---- JSON path (structured tree for tap-by-bounds on the server side) ----

    private fun appendNodeJson(
        node: AccessibilityNodeInfo,
        arr: JSONArray,
        depth: Int,
        maxDepth: Int,
        counter: () -> Unit
    ) {
        counter()
        if (depth > maxDepth) return
        val b = Rect()
        node.getBoundsInScreen(b)
        val o = JSONObject()
        o.put("index", 0)
        o.put("text", node.text?.toString() ?: "")
        o.put("resource_id", node.viewIdResourceName ?: "")
        o.put("class", node.className?.toString() ?: "")
        o.put("package", node.packageName?.toString() ?: "")
        o.put("content_desc", node.contentDescription?.toString() ?: "")
        o.put("clickable", node.isClickable)
        o.put("enabled", node.isEnabled)
        o.put("password", node.isPassword)
        o.put("scrollable", node.isScrollable)
        o.put("bounds", "[${b.left},${b.top}][${b.right},${b.bottom}]")
        val children = JSONArray()
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            appendNodeJson(child, children, depth + 1, maxDepth, counter)
        }
        o.put("children", children)
        arr.put(o)
    }

    private fun escape(s: String): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
