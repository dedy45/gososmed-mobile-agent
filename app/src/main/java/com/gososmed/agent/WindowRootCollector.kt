package com.gososmed.agent

/**
 * Window & Node hierarchy abstraction for snapshot collection (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 6).
 * Supports both Android runtime (AccessibilityService / AccessibilityWindowInfo)
 * and pure JVM simulations in unit tests.
 */
object WindowRootCollector {

    const val MAX_DEPTH = 16
    const val MAX_NODES = 2000

    /**
     * Budget tracker to prevent unbounded recursion or OOM on complex layouts.
     */
    class NodeBudget(val maxNodes: Int = MAX_NODES) {
        var count: Int = 0
            private set
        val exhausted: Boolean get() = count >= maxNodes

        fun acquire(): Boolean {
            if (exhausted) return false
            count++
            return true
        }
    }

    // Standard Android window types mirrored as constants for JVM test compatibility
    const val TYPE_APPLICATION = 1
    const val TYPE_INPUT_METHOD = 2
    const val TYPE_SYSTEM = 3
    const val TYPE_ACCESSIBILITY_OVERLAY = 4
    const val TYPE_SPLIT_SCREEN_DIVIDER = 5

    /**
     * Abstract representation of a window.
     */
    data class WindowDescriptor(
        val id: Int,
        val type: Int,
        val layer: Int = 0,
        val isFocused: Boolean = false,
        val isActive: Boolean = false,
        val rootNode: SimulatedNode? = null
    )

    /**
     * Pure data representation of a node in a hierarchy tree.
     */
    data class SimulatedNode(
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
        val children: List<SimulatedNode> = emptyList()
    )

    /**
     * Source interface for window hierarchy enumeration.
     */
    interface WindowTreeSource {
        fun getWindows(): List<WindowDescriptor>
    }

    /**
     * Live Android runtime implementation of [WindowTreeSource].
     * Enumerates live [android.view.accessibility.AccessibilityWindowInfo]s from [AccessibilityService],
     * or falls back to a single window with [AccessibilityService.rootInActiveWindow] if windows is empty/null.
     * Redacts password nodes (P1.4) and extracts [ActionableNodeRegistry.RawNodeInput].
     */
    class AndroidWindowTreeSource(
        private val service: android.accessibilityservice.AccessibilityService
    ) : WindowTreeSource {

        override fun getWindows(): List<WindowDescriptor> {
            val rawWindows = try {
                service.windows
            } catch (t: Throwable) {
                android.util.Log.w("GoAgent", "Failed to retrieve windows: ${t.message}", t)
                null
            }

            val budget = NodeBudget(MAX_NODES)
            if (!rawWindows.isNullOrEmpty()) {
                val descriptors = mutableListOf<WindowDescriptor>()
                for (w in rawWindows) {
                    if (budget.exhausted) break
                    val root = try {
                        w.root
                    } catch (t: Throwable) {
                        android.util.Log.w("GoAgent", "Failed to retrieve window root: ${t.message}", t)
                        null
                    }
                    val simulatedRoot = root?.let { convertNode(it, depth = 0, budget = budget) }
                    descriptors.add(
                        WindowDescriptor(
                            id = w.id,
                            type = w.type,
                            layer = w.layer,
                            isFocused = w.isFocused,
                            isActive = w.isActive,
                            rootNode = simulatedRoot
                        )
                    )
                }
                return descriptors
            }

            // Fallback to single active window
            val activeRoot = try {
                service.rootInActiveWindow
            } catch (t: Throwable) {
                android.util.Log.w("GoAgent", "Failed to retrieve rootInActiveWindow: ${t.message}", t)
                null
            }
            val simulatedRoot = activeRoot?.let { convertNode(it, depth = 0, budget = budget) }
            return listOf(
                WindowDescriptor(
                    id = 0,
                    type = TYPE_APPLICATION,
                    layer = 0,
                    isFocused = true,
                    isActive = true,
                    rootNode = simulatedRoot
                )
            )
        }

        companion object {
            fun convertNode(
                node: android.view.accessibility.AccessibilityNodeInfo,
                depth: Int = 0,
                budget: NodeBudget = NodeBudget()
            ): SimulatedNode {
                budget.acquire()

                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val bounds = SnapshotBounds(rect.left, rect.top, rect.right, rect.bottom)

                val isPassword = node.isPassword
                val rawText = node.text?.toString()
                val rawDesc = node.contentDescription?.toString()

                // P1.4 Password redaction: if node.isPassword == true, redact text and contentDescription
                val text = if (isPassword) "[REDACTED]" else rawText
                val desc = if (isPassword) "[REDACTED]" else rawDesc

                val children = mutableListOf<SimulatedNode>()
                if (depth < MAX_DEPTH && !budget.exhausted) {
                    val count = node.childCount
                    for (i in 0 until count) {
                        if (budget.exhausted) break
                        val child = try {
                            node.getChild(i)
                        } catch (t: Throwable) {
                            android.util.Log.w("GoAgent", "Failed to get child $i: ${t.message}", t)
                            null
                        }
                        if (child != null) {
                            children.add(convertNode(child, depth = depth + 1, budget = budget))
                        }
                    }
                }

                return SimulatedNode(
                    packageName = node.packageName?.toString() ?: "",
                    className = node.className?.toString() ?: "",
                    resourceId = node.viewIdResourceName,
                    text = text,
                    contentDescription = desc,
                    bounds = bounds,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    scrollable = node.isScrollable,
                    focusable = node.isFocusable,
                    focused = node.isFocused,
                    enabled = node.isEnabled,
                    selected = node.isSelected,
                    password = isPassword,
                    children = children
                )
            }
        }
    }

    /**
     * Collects raw nodes across all interactive windows.
     * Rejects agent's package and non-interactive window types.
     */
    fun collectRawNodes(source: WindowTreeSource): List<ActionableNodeRegistry.RawNodeInput> {
        val result = mutableListOf<ActionableNodeRegistry.RawNodeInput>()
        val windows = source.getWindows()

        // Filter relevant interactive windows
        val interactiveWindows = windows.filter { w ->
            w.type == TYPE_APPLICATION ||
                    w.type == TYPE_INPUT_METHOD ||
                    w.type == TYPE_SYSTEM ||
                    w.type == TYPE_ACCESSIBILITY_OVERLAY ||
                    w.type == TYPE_SPLIT_SCREEN_DIVIDER
        }.sortedBy { it.layer }

        for (window in interactiveWindows) {
            val root = window.rootNode ?: continue
            // Reject if root window belongs directly to agent
            if (root.packageName == ActionableNodeRegistry.AGENT_PACKAGE) {
                continue
            }

            traverseNode(
                node = root,
                windowId = window.id,
                ancestorPath = "",
                siblingIndex = 0,
                parentIndex = null,
                depth = 0,
                collector = result
            )

            if (result.size >= MAX_NODES) {
                break
            }
        }

        return result
    }

    private fun traverseNode(
        node: SimulatedNode,
        windowId: Int,
        ancestorPath: String,
        siblingIndex: Int,
        parentIndex: Int?,
        depth: Int,
        collector: MutableList<ActionableNodeRegistry.RawNodeInput>
    ) {
        if (depth > MAX_DEPTH || collector.size >= MAX_NODES) {
            return
        }

        // Reject agent package nodes
        if (node.packageName == ActionableNodeRegistry.AGENT_PACKAGE) {
            return
        }

        val currentIndex = collector.size
        val simpleClassName = node.className.substringAfterLast('.')
        val currentPath = if (ancestorPath.isEmpty()) simpleClassName else "$ancestorPath/$simpleClassName"

        val rawNode = ActionableNodeRegistry.RawNodeInput(
            windowId = windowId,
            packageName = node.packageName,
            className = node.className,
            resourceId = node.resourceId,
            text = node.text,
            contentDescription = node.contentDescription,
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
            ancestorPath = ancestorPath,
            siblingIndex = siblingIndex,
            childCount = node.children.size,
            parentIndex = parentIndex
        )
        collector.add(rawNode)

        for (idx in node.children.indices) {
            if (collector.size >= MAX_NODES) break
            traverseNode(
                node = node.children[idx],
                windowId = windowId,
                ancestorPath = currentPath,
                siblingIndex = idx,
                parentIndex = currentIndex,
                depth = depth + 1,
                collector = collector
            )
        }
    }
}
