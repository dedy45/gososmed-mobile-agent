package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Test

class ActionableNodeRegistryTest {

    @Test
    fun testGenerateNodeIdDeterministic() {
        val bounds = SnapshotBounds(100, 200, 300, 400)
        val id1 = ActionableNodeRegistry.generateNodeId(
            displayId = 0,
            windowId = 1,
            packageName = "com.facebook.katana",
            resourceId = "btn_post",
            className = "android.widget.Button",
            text = "Post",
            contentDescription = "Post Button",
            ancestorPath = "FrameLayout/ViewGroup/Button",
            siblingIndex = 0,
            bounds = bounds
        )

        val id2 = ActionableNodeRegistry.generateNodeId(
            displayId = 0,
            windowId = 1,
            packageName = "com.facebook.katana",
            resourceId = "btn_post",
            className = "android.widget.Button",
            text = "Post",
            contentDescription = "Post Button",
            ancestorPath = "FrameLayout/ViewGroup/Button",
            siblingIndex = 0,
            bounds = bounds
        )

        assertEquals(id1, id2)
        assertEquals(16, id1.length)

        // Different text produces different ID
        val id3 = ActionableNodeRegistry.generateNodeId(
            displayId = 0,
            windowId = 1,
            packageName = "com.facebook.katana",
            resourceId = "btn_post",
            className = "android.widget.Button",
            text = "Cancel",
            contentDescription = "Post Button",
            ancestorPath = "FrameLayout/ViewGroup/Button",
            siblingIndex = 0,
            bounds = bounds
        )
        assertNotEquals(id1, id3)
    }

    @Test
    fun testGenerateNodeIdQuantizedBoundsJitterResistant() {
        val bounds1 = SnapshotBounds(100, 200, 300, 400)
        val boundsWith1pxJitter = SnapshotBounds(101, 201, 301, 401)
        val id1 = ActionableNodeRegistry.generateNodeId(
            displayId = 0,
            windowId = 1,
            packageName = "com.facebook.katana",
            resourceId = "btn_post",
            className = "android.widget.Button",
            text = "Post",
            contentDescription = "Post Button",
            ancestorPath = "FrameLayout/ViewGroup/Button",
            siblingIndex = 0,
            bounds = bounds1
        )
        val id2 = ActionableNodeRegistry.generateNodeId(
            displayId = 0,
            windowId = 1,
            packageName = "com.facebook.katana",
            resourceId = "btn_post",
            className = "android.widget.Button",
            text = "Post",
            contentDescription = "Post Button",
            ancestorPath = "FrameLayout/ViewGroup/Button",
            siblingIndex = 0,
            bounds = boundsWith1pxJitter
        )
        assertEquals("1px layout jitter should produce identical node ID due to 4px quantization", id1, id2)
    }

    @Test
    fun testRejectsDisabledAndAgentPackageAndInvalidBounds() {
        val validBounds = SnapshotBounds(100, 100, 200, 200)

        // 1. Disabled
        val disabledNode = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            bounds = validBounds,
            enabled = false,
            clickable = true
        )
        assertTrue(ActionableNodeRegistry.shouldReject(disabledNode, 1080, 2400))

        // 2. Agent package
        val agentNode = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.gososmed.agent",
            className = "android.widget.Button",
            bounds = validBounds,
            enabled = true,
            clickable = true
        )
        assertTrue(ActionableNodeRegistry.shouldReject(agentNode, 1080, 2400))

        // 3. Negative bounds
        val negativeBoundsNode = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            bounds = SnapshotBounds(-50, 10, 100, 100),
            enabled = true,
            clickable = true
        )
        assertTrue(ActionableNodeRegistry.shouldReject(negativeBoundsNode, 1080, 2400))

        // 4. Area too small (< 10 px)
        val tinyNode = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            bounds = SnapshotBounds(10, 10, 12, 12), // 2x2 = 4 px^2
            enabled = true,
            clickable = true
        )
        assertTrue(ActionableNodeRegistry.shouldReject(tinyNode, 1080, 2400))

        // 5. Completely outside viewport
        val outsideNode = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            bounds = SnapshotBounds(2000, 3000, 2100, 3100),
            enabled = true,
            clickable = true
        )
        assertTrue(ActionableNodeRegistry.shouldReject(outsideNode, 1080, 2400))

        // 6. Decorative without semantic value
        val decorativeNode = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.app",
            className = "android.view.View",
            bounds = validBounds,
            enabled = true,
            clickable = false,
            text = null,
            contentDescription = null,
            resourceId = null
        )
        assertTrue(ActionableNodeRegistry.shouldReject(decorativeNode, 1080, 2400))
    }

    @Test
    fun testClickableAncestorLinking() {
        // Hierarchy:
        // Index 0: Clickable Container (ViewGroup, clickable=true)
        // Index 1: Informative TextView inside Container (clickable=false, text="Submit", parentIndex=0)
        val container = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.LinearLayout",
            resourceId = "container_submit",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true,
            parentIndex = null
        )
        val label = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.TextView",
            text = "Submit",
            bounds = SnapshotBounds(120, 120, 250, 180),
            clickable = false,
            parentIndex = 0
        )

        val processed = ActionableNodeRegistry.processNodes(
            rawNodes = listOf(container, label),
            displayId = 0,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertEquals(2, processed.size)
        val processedContainer = processed.first { it.className == "android.widget.LinearLayout" }
        val processedLabel = processed.first { it.className == "android.widget.TextView" }

        assertTrue(processedContainer.clickable)
        assertTrue(processedContainer.actionable)
        assertNull(processedContainer.clickableAncestorId)

        assertFalse(processedLabel.clickable)
        assertTrue(processedLabel.actionable)
        assertEquals(processedContainer.nodeId, processedLabel.clickableAncestorId)
    }

    @Test
    fun testEmptyHierarchyHandledGracefully() {
        val result = ActionableNodeRegistry.processNodes(
            rawNodes = emptyList(),
            displayId = 0,
            displayWidth = 1080,
            displayHeight = 2400
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun testPasswordRedactionReplacesTextAndDescription() {
        val passwordNode = ActionableNodeRegistry.RawNodeInput(
            windowId = 1,
            packageName = "com.example.bank",
            className = "android.widget.EditText",
            resourceId = "edit_password",
            text = "SuperSecretPassword123",
            contentDescription = "Secret password field",
            bounds = SnapshotBounds(100, 200, 500, 300),
            editable = true,
            password = true
        )

        val processed = ActionableNodeRegistry.processNodes(
            rawNodes = listOf(passwordNode),
            displayId = 0,
            displayWidth = 1080,
            displayHeight = 2400
        )

        assertEquals(1, processed.size)
        val node = processed[0]
        assertTrue(node.password)
        assertEquals("[REDACTED]", node.text)
        assertEquals("[REDACTED]", node.contentDescription)

        // Ensure plain password text never appears in JSON serialization
        val jsonStr = node.toJson().toString()
        assertFalse(jsonStr.contains("SuperSecretPassword123"))
        assertFalse(jsonStr.contains("Secret password field"))
        assertTrue(jsonStr.contains("[REDACTED]"))
    }

    @Test
    fun testHierarchyDepthBudgetAndMaxNodesLimit() {
        assertEquals(16, WindowRootCollector.MAX_DEPTH)
        assertEquals(2000, WindowRootCollector.MAX_NODES)

        // 1. Deep recursion test (depth 25 > 16)
        fun buildDeepChain(currentDepth: Int, maxDepth: Int): WindowRootCollector.SimulatedNode {
            val child = if (currentDepth < maxDepth) {
                listOf(buildDeepChain(currentDepth + 1, maxDepth))
            } else emptyList()
            return WindowRootCollector.SimulatedNode(
                packageName = "com.example.deep",
                className = "android.view.View",
                bounds = SnapshotBounds(10, 10, 100, 100),
                text = "Depth $currentDepth",
                children = child
            )
        }

        val deepRoot = buildDeepChain(0, 25)
        val deepSource = object : WindowRootCollector.WindowTreeSource {
            override fun getWindows(): List<WindowRootCollector.WindowDescriptor> = listOf(
                WindowRootCollector.WindowDescriptor(id = 1, type = WindowRootCollector.TYPE_APPLICATION, rootNode = deepRoot)
            )
        }
        val collectedDeep = WindowRootCollector.collectRawNodes(deepSource)
        // Maximum collected depth must be capped at MAX_DEPTH (16 levels = at most 17 nodes from 0..16)
        assertTrue(collectedDeep.size <= WindowRootCollector.MAX_DEPTH + 1)
        assertFalse("Deep recursion must terminate without StackOverflowError", collectedDeep.isEmpty())

        // 2. Broad node count test (> 2000 nodes)
        val wideChildren = (1..2500).map { i ->
            WindowRootCollector.SimulatedNode(
                packageName = "com.example.wide",
                className = "android.widget.TextView",
                bounds = SnapshotBounds(10, 10, 100, 100),
                text = "Item $i"
            )
        }
        val wideRoot = WindowRootCollector.SimulatedNode(
            packageName = "com.example.wide",
            className = "android.widget.LinearLayout",
            bounds = SnapshotBounds(0, 0, 1080, 2400),
            children = wideChildren
        )
        val wideSource = object : WindowRootCollector.WindowTreeSource {
            override fun getWindows(): List<WindowRootCollector.WindowDescriptor> = listOf(
                WindowRootCollector.WindowDescriptor(id = 2, type = WindowRootCollector.TYPE_APPLICATION, rootNode = wideRoot)
            )
        }
        val collectedWide = WindowRootCollector.collectRawNodes(wideSource)
        assertEquals(WindowRootCollector.MAX_NODES, collectedWide.size)
    }
}
