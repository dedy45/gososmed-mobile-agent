package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Test

class SnapshotCollectorTest {

    private class MockDisplayProvider(
        var display: SnapshotDisplay = SnapshotDisplay(0, 1080, 2400, 0, 420),
        var foreground: SnapshotForeground = SnapshotForeground("com.facebook.katana", 1),
        var mutateOnGet: Boolean = false,
        var mutationTarget: String = ""
    ) : SnapshotCollector.DisplayInfoProvider {
        private var getCount = 0

        override fun getDisplayMetrics(): SnapshotDisplay {
            getCount++
            if (mutateOnGet && getCount > 1 && mutationTarget == "rotation") {
                return display.copy(rotation = 1, width = 2400, height = 1080)
            }
            return display
        }

        override fun getForegroundPackage(): SnapshotForeground {
            if (mutateOnGet && getCount > 1 && mutationTarget == "foreground") {
                return foreground.copy(packageName = "com.android.launcher")
            }
            return foreground
        }
    }

    private class MockTreeSource(private val windows: List<WindowRootCollector.WindowDescriptor>) :
        WindowRootCollector.WindowTreeSource {
        override fun getWindows(): List<WindowRootCollector.WindowDescriptor> = windows
    }

    @Test
    fun testSuccessfulSnapshotCapture() {
        val rootNode = WindowRootCollector.SimulatedNode(
            packageName = "com.facebook.katana",
            className = "android.widget.FrameLayout",
            bounds = SnapshotBounds(0, 0, 1080, 2400),
            children = listOf(
                WindowRootCollector.SimulatedNode(
                    packageName = "com.facebook.katana",
                    className = "android.widget.Button",
                    resourceId = "com.facebook.katana:id/login",
                    text = "Log In",
                    bounds = SnapshotBounds(100, 500, 980, 600),
                    clickable = true
                )
            )
        )

        val window = WindowRootCollector.WindowDescriptor(
            id = 1,
            type = WindowRootCollector.TYPE_APPLICATION,
            rootNode = rootNode
        )

        val displayProvider = MockDisplayProvider()
        val treeSource = MockTreeSource(listOf(window))

        val result = SnapshotCollector.capture(
            displayProvider = displayProvider,
            treeSource = treeSource,
            frameSeq = 10L
        )

        assertTrue(result.quality.valid)
        assertFalse(result.quality.windowChanged)
        assertEquals(0.0, result.quality.invalidBoundsRatio, 0.001)
        assertEquals(1, result.nodes.size)
        assertEquals("Log In", result.nodes[0].text)
        assertTrue(result.treeHash.startsWith("sha256:"))
        assertTrue(result.screenFingerprint.isNotEmpty())
    }

    @Test
    fun testOrientationChangeDuringCaptureMarksQualityInvalid() {
        val rootNode = WindowRootCollector.SimulatedNode(
            packageName = "com.facebook.katana",
            className = "android.widget.Button",
            text = "Feed",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        val window = WindowRootCollector.WindowDescriptor(
            id = 1,
            type = WindowRootCollector.TYPE_APPLICATION,
            rootNode = rootNode
        )

        val displayProvider = MockDisplayProvider(mutateOnGet = true, mutationTarget = "rotation")
        val treeSource = MockTreeSource(listOf(window))

        val result = SnapshotCollector.capture(displayProvider, treeSource)

        assertFalse(result.quality.valid)
        assertTrue(result.quality.windowChanged)
        assertTrue(result.quality.reasons.contains("orientation_changed_during_capture"))
    }

    @Test
    fun testForegroundChangeDuringCaptureMarksQualityInvalid() {
        val rootNode = WindowRootCollector.SimulatedNode(
            packageName = "com.facebook.katana",
            className = "android.widget.Button",
            text = "Profile",
            bounds = SnapshotBounds(100, 100, 300, 200),
            clickable = true
        )
        val window = WindowRootCollector.WindowDescriptor(
            id = 1,
            type = WindowRootCollector.TYPE_APPLICATION,
            rootNode = rootNode
        )

        val displayProvider = MockDisplayProvider(mutateOnGet = true, mutationTarget = "foreground")
        val treeSource = MockTreeSource(listOf(window))

        val result = SnapshotCollector.capture(displayProvider, treeSource)

        assertFalse(result.quality.valid)
        assertTrue(result.quality.windowChanged)
        assertTrue(result.quality.reasons.contains("foreground_package_changed_during_capture"))
    }

    @Test
    fun testInvalidBoundsRatioThresholdViolation() {
        // Create 4 nodes: 2 with empty bounds, 2 with valid bounds -> ratio = 0.5 > 0.3
        val rootNode = WindowRootCollector.SimulatedNode(
            packageName = "com.example.app",
            className = "android.widget.LinearLayout",
            bounds = SnapshotBounds(0, 0, 1080, 2400),
            children = listOf(
                WindowRootCollector.SimulatedNode(
                    packageName = "com.example.app",
                    className = "android.widget.TextView",
                    text = "Bad 1",
                    bounds = SnapshotBounds(100, 100, 100, 100), // empty bounds width=0
                    clickable = true
                ),
                WindowRootCollector.SimulatedNode(
                    packageName = "com.example.app",
                    className = "android.widget.TextView",
                    text = "Bad 2",
                    bounds = SnapshotBounds(-20, 100, 100, 200), // negative bounds
                    clickable = true
                ),
                WindowRootCollector.SimulatedNode(
                    packageName = "com.example.app",
                    className = "android.widget.TextView",
                    text = "Good 1",
                    bounds = SnapshotBounds(100, 100, 300, 200),
                    clickable = true
                )
            )
        )
        val window = WindowRootCollector.WindowDescriptor(
            id = 1,
            type = WindowRootCollector.TYPE_APPLICATION,
            rootNode = rootNode
        )

        val displayProvider = MockDisplayProvider()
        val treeSource = MockTreeSource(listOf(window))

        val result = SnapshotCollector.capture(displayProvider, treeSource)

        assertFalse(result.quality.valid)
        assertTrue(result.quality.invalidBoundsRatio > SnapshotCollector.INVALID_BOUNDS_THRESHOLD)
        assertTrue(result.quality.reasons.contains("high_invalid_bounds_ratio"))
    }

    @Test
    fun testTreeHashDeterminism() {
        val nodeA = SnapshotNode(
            nodeId = "id-a",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "Click Me",
            bounds = SnapshotBounds(10, 10, 100, 100),
            clickable = true
        )
        val nodeB = SnapshotNode(
            nodeId = "id-b",
            windowId = 1,
            packageName = "com.example.app",
            className = "android.widget.TextView",
            text = "Title",
            bounds = SnapshotBounds(10, 110, 200, 150),
            clickable = false
        )

        val hash1 = SnapshotCollector.computeTreeHash(listOf(nodeA, nodeB))
        // Order shouldn't affect hash because computeTreeHash sorts by nodeId
        val hash2 = SnapshotCollector.computeTreeHash(listOf(nodeB, nodeA))

        assertEquals(hash1, hash2)
        assertTrue(hash1.startsWith("sha256:"))
    }

    @Test
    fun testRejectsAgentOwnPackage() {
        val agentRoot = WindowRootCollector.SimulatedNode(
            packageName = ActionableNodeRegistry.AGENT_PACKAGE,
            className = "android.widget.FrameLayout",
            bounds = SnapshotBounds(0, 0, 1, 1),
            children = listOf(
                WindowRootCollector.SimulatedNode(
                    packageName = ActionableNodeRegistry.AGENT_PACKAGE,
                    className = "android.view.View",
                    bounds = SnapshotBounds(0, 0, 1, 1),
                    clickable = true
                )
            )
        )

        val window = WindowRootCollector.WindowDescriptor(
            id = 99,
            type = WindowRootCollector.TYPE_APPLICATION,
            rootNode = agentRoot
        )

        val displayProvider = MockDisplayProvider()
        val treeSource = MockTreeSource(listOf(window))

        val result = SnapshotCollector.capture(displayProvider, treeSource)

        assertEquals(0, result.nodes.size)
    }

    @Test
    fun testNodeBudgetTrackingAndExhaustion() {
        val budget = WindowRootCollector.NodeBudget(maxNodes = 3)
        assertFalse(budget.exhausted)
        assertEquals(0, budget.count)

        assertTrue(budget.acquire())
        assertEquals(1, budget.count)
        assertTrue(budget.acquire())
        assertEquals(2, budget.count)
        assertTrue(budget.acquire())
        assertEquals(3, budget.count)
        assertTrue(budget.exhausted)

        // Subsequent acquires must return false
        assertFalse(budget.acquire())
        assertEquals(3, budget.count)
    }

    @Test
    fun testDepthLimitInHierarchyTraversal() {
        // Construct a deeply nested tree: depth 20 (> MAX_DEPTH 16)
        var leaf = WindowRootCollector.SimulatedNode(
            packageName = "com.example.app",
            className = "android.widget.TextView",
            text = "Deepest Leaf",
            bounds = SnapshotBounds(10, 10, 50, 50),
            clickable = true
        )
        for (depth in 19 downTo 0) {
            leaf = WindowRootCollector.SimulatedNode(
                packageName = "com.example.app",
                className = "android.widget.FrameLayout",
                bounds = SnapshotBounds(0, 0, 100, 100),
                children = listOf(leaf)
            )
        }

        val window = WindowRootCollector.WindowDescriptor(
            id = 1,
            type = WindowRootCollector.TYPE_APPLICATION,
            rootNode = leaf
        )

        val rawNodes = WindowRootCollector.collectRawNodes(MockTreeSource(listOf(window)))
        // Depths 0 through 16 = at most 17 nodes; nodes beyond MAX_DEPTH (16) must not be collected
        assertTrue(rawNodes.size <= WindowRootCollector.MAX_DEPTH + 1)
        assertFalse(rawNodes.any { it.text == "Deepest Leaf" })
    }

    @Test
    fun testMaxNodesCapInHierarchyTraversal() {
        // Construct a wide tree with 2500 children (> MAX_NODES 2000)
        val children = (1..2500).map { i ->
            WindowRootCollector.SimulatedNode(
                packageName = "com.example.app",
                className = "android.widget.Button",
                text = "Button $i",
                bounds = SnapshotBounds(0, 0, 10, 10),
                clickable = true
            )
        }
        val root = WindowRootCollector.SimulatedNode(
            packageName = "com.example.app",
            className = "android.widget.LinearLayout",
            bounds = SnapshotBounds(0, 0, 1080, 2400),
            children = children
        )
        val window = WindowRootCollector.WindowDescriptor(
            id = 1,
            type = WindowRootCollector.TYPE_APPLICATION,
            rootNode = root
        )

        val rawNodes = WindowRootCollector.collectRawNodes(MockTreeSource(listOf(window)))
        assertEquals(WindowRootCollector.MAX_NODES, rawNodes.size)
    }
}
