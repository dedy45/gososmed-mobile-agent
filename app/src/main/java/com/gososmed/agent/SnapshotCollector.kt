package com.gososmed.agent

import java.security.MessageDigest
import java.util.UUID

/**
 * Snapshot Collector (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 6).
 * Coordinates snapshot collection across interactive windows, verifies stability gates
 * (foreground package change, orientation change, invalid bounds ratio),
 * computes tree hashes and screen fingerprints.
 */
object SnapshotCollector {

    const val INVALID_BOUNDS_THRESHOLD = 0.3

    interface DisplayInfoProvider {
        fun getDisplayMetrics(): SnapshotDisplay
        fun getForegroundPackage(): SnapshotForeground
    }

    /**
     * Live Android runtime implementation of [DisplayInfoProvider].
     * Reads display metrics from [Context.resources.displayMetrics] or [WindowManager],
     * and foreground package/window from [AgentAccessibilityService].
     */
    class AndroidDisplayInfoProvider(
        private val context: android.content.Context,
        private val service: AgentAccessibilityService
    ) : DisplayInfoProvider {

        override fun getDisplayMetrics(): SnapshotDisplay {
            val metrics = context.resources.displayMetrics
            val rotation = try {
                val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                val defaultDisplay = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (defaultDisplay != null) {
                    defaultDisplay.rotation
                } else {
                    val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
                }
            } catch (t: Throwable) {
                0
            }

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val densityDpi = metrics.densityDpi

            return SnapshotDisplay(
                id = 0,
                width = width,
                height = height,
                rotation = rotation,
                densityDpi = densityDpi
            )
        }

        override fun getForegroundPackage(): SnapshotForeground {
            val pkg = service.currentPackage().ifEmpty { "unknown" }
            val root = try {
                service.rootInActiveWindow
            } catch (t: Throwable) {
                android.util.Log.w("GoAgent", "Failed to get rootInActiveWindow: ${t.message}", t)
                null
            }
            val windowId = try {
                root?.windowId ?: 0
            } catch (t: Throwable) {
                android.util.Log.w("GoAgent", "Failed to get windowId: ${t.message}", t)
                0
            }
            return SnapshotForeground(
                packageName = pkg,
                windowId = windowId
            )
        }
    }

    /**
     * Captures a snapshot with pre/post stability gates.
     */
    fun capture(
        displayProvider: DisplayInfoProvider,
        treeSource: WindowRootCollector.WindowTreeSource,
        frameSeq: Long = 1L,
        snapshotId: String = UUID.randomUUID().toString(),
        image: SnapshotImage? = null
    ): SnapshotResult {
        val capturedAtMs = System.currentTimeMillis()

        // 1. Pre-capture checks
        val preDisplay = displayProvider.getDisplayMetrics()
        val preForeground = displayProvider.getForegroundPackage()

        // 2. Window enumeration and raw node collection with pre-cap counting (§2.10.j.3)
        val rawResult = WindowRootCollector.collectRawNodesWithCount(treeSource)
        val rawNodes = rawResult.nodes
        val totalDiscoveredCount = rawResult.totalCount
        val truncated = rawResult.truncated
        // 3. Post-capture checks
        val postDisplay = displayProvider.getDisplayMetrics()
        val postForeground = displayProvider.getForegroundPackage()

        val qualityReasons = mutableListOf<String>()
        var windowChanged = false

        if (preDisplay.rotation != postDisplay.rotation || preDisplay.width != postDisplay.width || preDisplay.height != postDisplay.height) {
            windowChanged = true
            qualityReasons.add("orientation_changed_during_capture")
        }

        if (preForeground.packageName != postForeground.packageName) {
            windowChanged = true
            qualityReasons.add("foreground_package_changed_during_capture")
        }

        // 4. Actionable filtering and node ID generation
        val processedNodes = ActionableNodeRegistry.processNodes(
            rawNodes = rawNodes,
            displayId = preDisplay.id,
            displayWidth = preDisplay.width,
            displayHeight = preDisplay.height
        )

        // 5. Evaluate invalid bounds ratio from raw nodes
        val totalRawCount = rawNodes.size
        val invalidBoundsCount = rawNodes.count { it.bounds.isEmpty || it.bounds.isNegative }
        val invalidRatio = if (totalRawCount > 0) invalidBoundsCount.toDouble() / totalRawCount.toDouble() else 0.0

        if (invalidRatio > INVALID_BOUNDS_THRESHOLD) {
            qualityReasons.add("high_invalid_bounds_ratio")
        }

        val isValid = !windowChanged && invalidRatio <= INVALID_BOUNDS_THRESHOLD

        val quality = SnapshotQuality(
            valid = isValid,
            invalidBoundsRatio = invalidRatio,
            windowChanged = windowChanged,
            reasons = qualityReasons
        )

        // 6. Compute tree hash
        val treeHash = computeTreeHash(processedNodes)

        // 6b. Compute deterministic order-independent nodes_digest (§2.8)
        val nodesDigest = computeNodesDigest(processedNodes)

        // 7. Compute screen fingerprint
        val screenFingerprint = computeScreenFingerprint(
            packageName = postForeground.packageName,
            windowId = postForeground.windowId,
            nodes = processedNodes
        )
        return SnapshotResult(
            snapshotId = snapshotId,
            frameSeq = frameSeq,
            capturedAtMs = capturedAtMs,
            display = postDisplay,
            foreground = postForeground,
            treeHash = treeHash,
            screenFingerprint = screenFingerprint,
            quality = quality,
            nodes = processedNodes,
            image = image,
            truncated = truncated,
            nodesDigest = nodesDigest,
            nodeCount = totalDiscoveredCount
        )
    }

    /**
     * SHA-256 tree hash over sorted node signatures.
     */
    fun computeTreeHash(nodes: List<SnapshotNode>): String {
        val sortedNodes = nodes.sortedBy { it.nodeId }
        val sb = StringBuilder()
        for (node in sortedNodes) {
            sb.append(node.nodeId)
                .append('|')
                .append(node.className)
                .append('|')
                .append(node.bounds.left).append(',').append(node.bounds.top).append(',').append(node.bounds.right).append(',').append(node.bounds.bottom)
                .append('|')
                .append(node.text ?: "")
                .append('|')
                .append(node.contentDescription ?: "")
                .append(';')
        }
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Deterministic, traversal-order-independent, node_id-free SHA-256 digest
     * over canonical (resourceId, className, text, bounds) identity tuples.
     * (system-design-feedback-orpv-2026-10-23.md §2.8 & §5.2b).
     */
    fun computeNodesDigest(nodes: List<SnapshotNode>): String {
        val signatures = nodes.map { node ->
            val res = node.resourceId ?: "\u0000NULL\u0000"
            val cls = node.className
            val txt = node.text ?: "\u0000NULL\u0000"
            val b = node.bounds
            "$res|$cls|$txt|${b.left},${b.top},${b.right},${b.bottom}"
        }.sorted()

        val joined = signatures.joinToString("\n")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(joined.toByteArray(Charsets.UTF_8))
        return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes screen fingerprint for screen state classification.
     */
    fun computeScreenFingerprint(
        packageName: String,
        windowId: Int,
        nodes: List<SnapshotNode>
    ): String {
        val roleCounts = mutableMapOf<String, Int>()
        val stableTexts = mutableListOf<String>()

        for (node in nodes) {
            val role = node.className.substringAfterLast('.')
            roleCounts[role] = (roleCounts[role] ?: 0) + 1
            if (!node.text.isNullOrBlank() && node.text.length <= 40) {
                stableTexts.add(node.text.trim())
            }
        }
        val sortedRoles = roleCounts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        val sortedTexts = stableTexts.sorted().take(20).joinToString(",")

        val rawFingerprint = "$packageName:$windowId:[$sortedRoles]:[$sortedTexts]"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(rawFingerprint.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.substring(0, 32)
    }
}
