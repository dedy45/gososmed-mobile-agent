package com.gososmed.agent

import java.security.MessageDigest

/**
 * Actionable Node Registry for snapshot processing (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 7).
 * Filters actionable nodes, rejects non-actionable/invalid nodes, resolves clickable ancestors,
 * and generates stable snapshot-scoped node IDs.
 */
object ActionableNodeRegistry {

    const val AGENT_PACKAGE = "com.gososmed.agent"
    const val MIN_AREA = 10L // area must be >= 10 px^2

    data class RawNodeInput(
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
        val parentIndex: Int? = null // index of parent in raw nodes list
    )

    /**
     * Generates a stable snapshot-scoped node_id using SHA-256 truncated to 16 hex chars.
     */
    fun generateNodeId(
        displayId: Int,
        windowId: Int,
        packageName: String,
        resourceId: String?,
        className: String,
        text: String?,
        contentDescription: String?,
        ancestorPath: String,
        siblingIndex: Int,
        bounds: SnapshotBounds
    ): String {
        val normResourceId = resourceId?.trim() ?: ""
        val normText = text?.trim() ?: ""
        val normDesc = contentDescription?.trim() ?: ""
        // Quantize bounds to nearest 4 pixels (P1.5) to prevent 1px layout jitter from breaking node IDs
        val qLeft = (bounds.left / 4) * 4
        val qTop = (bounds.top / 4) * 4
        val qRight = (bounds.right / 4) * 4
        val qBottom = (bounds.bottom / 4) * 4
        val rawKey = "$displayId:$windowId:$packageName:$normResourceId:$className:$normText:$normDesc:$ancestorPath:$siblingIndex:[$qLeft,$qTop,$qRight,$qBottom]"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(rawKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /**
     * Checks if a node qualifies as directly actionable or informative child.
     */
    fun isDirectlyActionable(input: RawNodeInput): Boolean {
        return input.clickable ||
                input.longClickable ||
                input.editable ||
                input.checkable ||
                input.scrollable ||
                (input.focusable && (input.clickable || input.editable))
    }

    /**
     * Checks if node carries semantic textual or description value.
     */
    fun isInformative(input: RawNodeInput): Boolean {
        val hasText = !input.text.isNullOrBlank()
        val hasDesc = !input.contentDescription.isNullOrBlank()
        val hasId = !input.resourceId.isNullOrBlank()
        return hasText || hasDesc || hasId
    }

    /**
     * Rejection rules from Section 7:
     * - disabled;
     * - bounds empty or negative;
     * - completely outside display viewport;
     * - area very small (< 10 px^2);
     * - agent's own package;
     * - decorative node without semantic value.
     */
    fun shouldReject(
        input: RawNodeInput,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean {
        if (!input.enabled) return true
        if (input.bounds.isEmpty || input.bounds.isNegative) return true
        if (input.bounds.area < MIN_AREA) return true
        if (input.packageName == AGENT_PACKAGE) return true

        // Viewport check: completely outside display bounds
        val displayBounds = SnapshotBounds(0, 0, displayWidth, displayHeight)
        if (!displayBounds.intersects(input.bounds)) return true

        // Reject decorative node without semantic value AND not directly actionable
        if (!isDirectlyActionable(input) && !isInformative(input)) {
            return true
        }

        return false
    }

    /**
     * Processes a flat or pre-ordered list of raw nodes, resolves clickable ancestors,
     * filters non-actionable nodes, and outputs valid `SnapshotNode`s.
     */
    fun processNodes(
        rawNodes: List<RawNodeInput>,
        displayId: Int,
        displayWidth: Int,
        displayHeight: Int
    ): List<SnapshotNode> {
        // Step 1: Pre-calculate generated node IDs for all nodes to allow ancestor referencing
        val nodeIds = rawNodes.map { node ->
            val safeText = if (node.password) "[REDACTED]" else node.text
            val safeDesc = if (node.password) "[REDACTED]" else node.contentDescription
            generateNodeId(
                displayId = displayId,
                windowId = node.windowId,
                packageName = node.packageName,
                resourceId = node.resourceId,
                className = node.className,
                text = safeText,
                contentDescription = safeDesc,
                ancestorPath = node.ancestorPath,
                siblingIndex = node.siblingIndex,
                bounds = node.bounds
            )
        }

        // Step 2: For each node, find the closest clickable ancestor if it is not directly clickable
        val clickableAncestorIds = arrayOfNulls<String>(rawNodes.size)
        for (i in rawNodes.indices) {
            val current = rawNodes[i]
            if (isDirectlyActionable(current)) {
                clickableAncestorIds[i] = null
            } else {
                var parentIdx = current.parentIndex
                while (parentIdx != null && parentIdx in rawNodes.indices) {
                    val ancestor = rawNodes[parentIdx]
                    if (ancestor.enabled && (ancestor.clickable || ancestor.longClickable)) {
                        clickableAncestorIds[i] = nodeIds[parentIdx]
                        break
                    }
                    parentIdx = ancestor.parentIndex
                }
            }
        }

        // Step 3: Filter & build SnapshotNode list
        val result = mutableListOf<SnapshotNode>()
        for (i in rawNodes.indices) {
            val node = rawNodes[i]
            if (shouldReject(node, displayWidth, displayHeight)) {
                continue
            }

            val directAction = isDirectlyActionable(node)
            val clickableAncestorId = clickableAncestorIds[i]
            val actionable = directAction || (clickableAncestorId != null)

            val safeText = if (node.password) "[REDACTED]" else node.text
            val safeDesc = if (node.password) "[REDACTED]" else node.contentDescription
            if (actionable || isInformative(node)) {
                result.add(
                    SnapshotNode(
                        nodeId = nodeIds[i],
                        windowId = node.windowId,
                        packageName = node.packageName,
                        className = node.className,
                        resourceId = node.resourceId,
                        text = safeText,
                        contentDescription = safeDesc,
                        bounds = node.bounds,
                        clickable = node.clickable,
                        longClickable = node.longClickable,
                        editable = node.editable,
                        checkable = node.checkable,
                        checked = node.checked,
                        scrollable = node.scrollable,
                        focusable = node.focusable,
                        focused = node.focused,
                        enabled = node.enabled,
                        selected = node.selected,
                        password = node.password,
                        ancestorPath = node.ancestorPath,
                        siblingIndex = node.siblingIndex,
                        childCount = node.childCount,
                        actionable = actionable,
                        clickableAncestorId = clickableAncestorId
                    )
                )
            }
        }

        return result
    }
}
