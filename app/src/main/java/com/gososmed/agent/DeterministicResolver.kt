package com.gososmed.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic Node Resolver (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Section 8).
 * Evaluates candidate nodes against SelectorQuery using the 8 tiers:
 *   1. view/resource ID exact (weight 100)
 *   2. content description exact (weight 85)
 *   3. text exact (weight 80)
 *   4. text contains / locale alias (weight 65)
 *   5. role + ancestor/descendant relation (weight 50)
 *   6. zone / relative bounds (weight 35)
 *   7. OCR / vision hint (weight 25)
 *   8. raw coordinate contains (weight 15)
 *
 * Implements strict ambiguity stops when candidates are too close.
 */
object DeterministicResolver {

    const val AMBIGUITY_THRESHOLD = 10

    data class CandidateScore(
        val node: SnapshotNode,
        val score: Int,
        val matchedTiers: List<String>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("node_id", node.nodeId)
            put("score", score)
            put("matched_tiers", JSONArray(matchedTiers))
        }

        companion object {
            fun fromJson(json: JSONObject, nodeLookup: (String) -> SnapshotNode?): CandidateScore? {
                val nodeId = json.getString("node_id")
                val node = nodeLookup(nodeId) ?: return null
                val score = json.getInt("score")
                val tiers = mutableListOf<String>()
                val arr = json.optJSONArray("matched_tiers")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        tiers.add(arr.getString(i))
                    }
                }
                return CandidateScore(node, score, tiers)
            }
        }
    }

    data class ResolveResult(
        val selectedNode: SnapshotNode?,
        val score: Int,
        val candidates: List<CandidateScore>,
        val isAmbiguous: Boolean,
        val reasonCode: String?, // no_node, ambiguous, or null on success
        val clickableAncestorId: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("selected_node", selectedNode?.toJson())
            put("score", score)
            put("is_ambiguous", isAmbiguous)
            if (reasonCode != null) put("reason_code", reasonCode)
            if (clickableAncestorId != null) put("clickable_ancestor_id", clickableAncestorId)
            val candArr = JSONArray()
            for (c in candidates) {
                candArr.put(c.toJson())
            }
            put("candidates", candArr)
        }
    }

    /**
     * Resolves nodes from snapshot according to selector query.
     */
    fun resolve(
        query: SelectorQuery,
        nodes: List<SnapshotNode>,
        displayWidth: Int = 1080,
        displayHeight: Int = 2400
    ): ResolveResult {
        // Fast-path: direct nodeId match if specified
        if (!query.nodeId.isNullOrBlank()) {
            val directMatch = nodes.firstOrNull { it.nodeId == query.nodeId }
            return if (directMatch != null) {
                ResolveResult(
                    selectedNode = directMatch,
                    score = 100,
                    candidates = listOf(CandidateScore(directMatch, 100, listOf("node_id_exact"))),
                    isAmbiguous = false,
                    reasonCode = null,
                    clickableAncestorId = directMatch.clickableAncestorId
                )
            } else {
                ResolveResult(
                    selectedNode = null,
                    score = 0,
                    candidates = emptyList(),
                    isAmbiguous = false,
                    reasonCode = ProtocolV2.ReasonCodes.NO_NODE,
                    clickableAncestorId = null
                )
            }
        }

        // Package match validation if specified in query
        if (!query.packageName.isNullOrBlank()) {
            val qPkg = query.packageName.trim()
            if (nodes.isNotEmpty() && nodes.none { it.packageName.equals(qPkg, ignoreCase = true) }) {
                return ResolveResult(
                    selectedNode = null,
                    score = 0,
                    candidates = emptyList(),
                    isAmbiguous = false,
                    reasonCode = ProtocolV2.ReasonCodes.WRONG_PACKAGE,
                    clickableAncestorId = null
                )
            }
        }

        val evaluatedCandidates = mutableListOf<CandidateScore>()

        // Pre-resolve spatial anchors (Maestro Parity)
        val rightOfAnchor = query.rightOf?.let { findAnchorNode(it, nodes) }
        val leftOfAnchor = query.leftOf?.let { findAnchorNode(it, nodes) }
        val belowAnchor = query.below?.let { findAnchorNode(it, nodes) }
        val aboveAnchor = query.above?.let { findAnchorNode(it, nodes) }

        for (node in nodes) {
            if (!query.packageName.isNullOrBlank() && !node.packageName.equals(query.packageName.trim(), ignoreCase = true)) {
                continue
            }
            var score = 0
            val matchedTiers = mutableListOf<String>()

            // Tier 1: Resource ID exact
            if (!query.resourceId.isNullOrBlank() && !node.resourceId.isNullOrBlank()) {
                val qId = query.resourceId.trim()
                val nId = node.resourceId.trim()
                if (nId.equals(qId, ignoreCase = true) || nId.endsWith("/$qId", ignoreCase = true)) {
                    score += 100
                    matchedTiers.add("resource_id_exact")
                }
            }

            // Tier 2: Content description exact
            if (!query.contentDescription.isNullOrBlank() && !node.contentDescription.isNullOrBlank()) {
                if (node.contentDescription.trim().equals(query.contentDescription.trim(), ignoreCase = true)) {
                    score += 85
                    matchedTiers.add("content_desc_exact")
                }
            }

            // Tier 3: Text exact
            if (!query.text.isNullOrBlank() && !node.text.isNullOrBlank()) {
                val qText = query.text.trim()
                val nText = node.text.trim()
                if (nText.equals(qText, ignoreCase = true)) {
                    score += 80
                    matchedTiers.add("text_exact")
                } else if (nText.contains(qText, ignoreCase = true)) {
                    // Tier 4: Text contains
                    score += 65
                    matchedTiers.add("text_contains")
                }
            }

            // Tier 5: Role / ClassName plus ancestor path
            if (!query.className.isNullOrBlank()) {
                val qClass = query.className.trim()
                if (node.className.equals(qClass, ignoreCase = true) || node.className.endsWith(".$qClass", ignoreCase = true)) {
                    score += 50
                    matchedTiers.add("class_exact")
                }
            }
            if (!query.xpath.isNullOrBlank()) {
                if (node.ancestorPath.contains(query.xpath.trim(), ignoreCase = true)) {
                    score += 20
                    matchedTiers.add("ancestor_match")
                }
            }

            // Tier 6: Zone / Relative bounds
            if (!query.relativeZone.isNullOrBlank()) {
                val matchesZone = when (query.relativeZone.lowercase().trim()) {
                    "top_half" -> node.bounds.centerY < displayHeight / 2
                    "bottom_half" -> node.bounds.centerY >= displayHeight / 2
                    "center" -> {
                        val midY = displayHeight / 2
                        val midX = displayWidth / 2
                        Math.abs(node.bounds.centerY - midY) < displayHeight / 4 &&
                                Math.abs(node.bounds.centerX - midX) < displayWidth / 4
                    }
                    else -> false
                }
                if (matchesZone) {
                    score += 35
                    matchedTiers.add("relative_zone")
                }
            }

            // Tier 7: OCR / Vision hint
            if (!query.ocrHint.isNullOrBlank()) {
                val hint = query.ocrHint.trim()
                val hasMatchInText = node.text?.contains(hint, ignoreCase = true) == true
                val hasMatchInDesc = node.contentDescription?.contains(hint, ignoreCase = true) == true
                if (hasMatchInText || hasMatchInDesc) {
                    score += 25
                    matchedTiers.add("ocr_hint_match")
                }
            }

            // Tier 8: Raw coordinate fallback
            if (query.point != null) {
                if (node.bounds.contains(query.point.first, query.point.second)) {
                    score += 15
                    matchedTiers.add("point_contains")
                }
            }
            // Tier 9: Spatial 2D Layout Filtering (Maestro Parity)
            var spatialViolated = false

            if (rightOfAnchor != null) {
                if (node.nodeId == rightOfAnchor.nodeId) {
                    spatialViolated = true
                } else if (node.bounds.left >= rightOfAnchor.bounds.centerX &&
                    Math.abs(node.bounds.centerY - rightOfAnchor.bounds.centerY) <= Math.max(rightOfAnchor.bounds.height, node.bounds.height) * 3) {
                    val dist = Math.max(0, node.bounds.left - rightOfAnchor.bounds.right)
                    val bonus = 75 - (dist / 10).coerceAtMost(30)
                    score += bonus
                    matchedTiers.add("spatial_right_of")
                } else {
                    spatialViolated = true
                }
            }

            if (leftOfAnchor != null) {
                if (node.nodeId == leftOfAnchor.nodeId) {
                    spatialViolated = true
                } else if (node.bounds.right <= leftOfAnchor.bounds.centerX &&
                    Math.abs(node.bounds.centerY - leftOfAnchor.bounds.centerY) <= Math.max(leftOfAnchor.bounds.height, node.bounds.height) * 3) {
                    val dist = Math.max(0, leftOfAnchor.bounds.left - node.bounds.right)
                    val bonus = 75 - (dist / 10).coerceAtMost(30)
                    score += bonus
                    matchedTiers.add("spatial_left_of")
                } else {
                    spatialViolated = true
                }
            }

            if (belowAnchor != null) {
                if (node.nodeId == belowAnchor.nodeId) {
                    spatialViolated = true
                } else if (node.bounds.top >= belowAnchor.bounds.centerY &&
                    Math.abs(node.bounds.centerX - belowAnchor.bounds.centerX) <= Math.max(belowAnchor.bounds.width, node.bounds.width) * 3) {
                    val dist = Math.max(0, node.bounds.top - belowAnchor.bounds.bottom)
                    val bonus = 75 - (dist / 10).coerceAtMost(30)
                    score += bonus
                    matchedTiers.add("spatial_below")
                } else {
                    spatialViolated = true
                }
            }

            if (aboveAnchor != null) {
                if (node.nodeId == aboveAnchor.nodeId) {
                    spatialViolated = true
                } else if (node.bounds.bottom <= aboveAnchor.bounds.centerY &&
                    Math.abs(node.bounds.centerX - aboveAnchor.bounds.centerX) <= Math.max(aboveAnchor.bounds.width, node.bounds.width) * 3) {
                    val dist = Math.max(0, aboveAnchor.bounds.top - node.bounds.bottom)
                    val bonus = 75 - (dist / 10).coerceAtMost(30)
                    score += bonus
                    matchedTiers.add("spatial_above")
                } else {
                    spatialViolated = true
                }
            }

            if (spatialViolated) {
                score = 0
            }

            if (score > 0) {
                evaluatedCandidates.add(CandidateScore(node, score, matchedTiers))
            }
        }

        if (evaluatedCandidates.isEmpty()) {
            return ResolveResult(
                selectedNode = null,
                score = 0,
                candidates = emptyList(),
                isAmbiguous = false,
                reasonCode = ProtocolV2.ReasonCodes.NO_NODE,
                clickableAncestorId = null
            )
        }

        // Sort descending by score. In case of tie, sort smaller area first (more specific leaf)
        val sorted = evaluatedCandidates.sortedWith(
            compareByDescending<CandidateScore> { it.score }
                .thenBy { it.node.bounds.area }
        )

        val top = sorted[0]

        // Ambiguity check:
        // If more than 1 candidate, check if runner-up is within AMBIGUITY_THRESHOLD
        if (sorted.size > 1) {
            val runnerUp = sorted[1]
            val scoreDiff = top.score - runnerUp.score
            if (scoreDiff < AMBIGUITY_THRESHOLD) {
                return ResolveResult(
                    selectedNode = null,
                    score = top.score,
                    candidates = sorted,
                    isAmbiguous = true,
                    reasonCode = ProtocolV2.ReasonCodes.AMBIGUOUS,
                    clickableAncestorId = null
                )
            }
        }

        return ResolveResult(
            selectedNode = top.node,
            score = top.score,
            candidates = sorted,
            isAmbiguous = false,
            reasonCode = null,
            clickableAncestorId = top.node.clickableAncestorId
        )
    }

    private fun findAnchorNode(anchorKey: String, nodes: List<SnapshotNode>): SnapshotNode? {
        val trimmed = anchorKey.trim()
        return nodes.firstOrNull { node ->
            node.text?.equals(trimmed, ignoreCase = true) == true ||
            node.contentDescription?.equals(trimmed, ignoreCase = true) == true ||
            node.resourceId?.equals(trimmed, ignoreCase = true) == true ||
            node.resourceId?.endsWith("/$trimmed", ignoreCase = true) == true ||
            node.text?.contains(trimmed, ignoreCase = true) == true
        }
    }
}
