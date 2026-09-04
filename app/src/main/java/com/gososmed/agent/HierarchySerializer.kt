package com.gososmed.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
 *
 * Batasan dump (GAP-K6, Plan 07): MAX_NODES kini DITERAPKAN — layar berat
 * tidak lagi menghasilkan dump raksasa; saat kuota habis, traversal berhenti
 * dan `<hierarchy truncated="true">` menandai hasil yang terpotong (atribut
 * tak dikenal diabaikan aman oleh parser Go encoding/xml).
 */
object HierarchySerializer {

    // Limit flattening depth and node count to keep the dump bounded.
    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 2000

    /** Kuota node per dump; habis = traversal berhenti, dump ditandai truncated. */
    private class NodeBudget(private val max: Int) {
        var used = 0
            private set
        val exhausted: Boolean get() = used >= max

        /** Catat satu node; false bila kuota habis (pemanggil tidak menulis). */
        fun spend(): Boolean {
            if (exhausted) return false
            used++
            return true
        }
    }

    fun dump(root: AccessibilityNodeInfo?): String {
        if (root == null) {
            return "<hierarchy rotation=\"0\">\n</hierarchy>\n"
        }
        val body = StringBuilder()
        val budget = NodeBudget(MAX_NODES)
        appendNode(root, body, 0, 0, MAX_DEPTH, budget)
        val sb = StringBuilder()
        sb.append("<hierarchy rotation=\"0\"")
        if (budget.exhausted) sb.append(" truncated=\"true\"")
        sb.append(">\n")
        sb.append(body)
        sb.append("</hierarchy>\n")
        return sb.toString()
    }

    /**
     * Compact summary of a single window root: the root's package, class,
     * text snippet and descendant count. Used by `dumpWindows` to show what
     * each getWindows() window exposes WITHOUT shipping the whole subtree in
     * the probe response.
     */
    fun summarize(root: AccessibilityNodeInfo?): JSONObject {
        val o = JSONObject()
        if (root == null) return o
        o.put("package", root.packageName?.toString() ?: "")
        o.put("class", root.className?.toString() ?: "")
        val text = root.text?.toString() ?: root.contentDescription?.toString() ?: ""
        o.put("text", text.take(80))
        o.put("childCount", countNodes(root))
        return o
    }

    private fun countNodes(root: AccessibilityNodeInfo): Int {
        var n = 1
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val c = cur.childCount
            for (i in 0 until c) {
                val child = cur.getChild(i) ?: continue
                stack.add(child)
                n++
            }
        }
        return n
    }

    // ---- XML path ----

    private fun appendNode(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        index: Int,
        maxDepth: Int,
        budget: NodeBudget
    ) {
        if (!budget.spend()) return
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
            appendNode(child, sb, depth + 1, i, maxDepth, budget)
        }
        sb.append("  ".repeat(depth)).append("</node>\n")
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
