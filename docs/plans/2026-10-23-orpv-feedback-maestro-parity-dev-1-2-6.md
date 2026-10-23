# GoSosmed Mobile Agent Dev 1.2.6 Implementation Plan: ORPV Feedback Layer & Maestro Parity

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement ORPV feedback fixes (Temuan #0-#5, #7, #12, #14) and high-value Maestro primitives (Spatial Filtering, Native scrollUntilVisible, Stale Cache Refresh, Toast Detection) in GoSosmed Mobile Agent version 1.2.6-dev.1 (versionCode 40) with full verification.

**Architecture:** Maintain single-process zero-crash Kotlin Android architecture with 100% JVM-unit-testable domain abstractions. Fix the core action-chaining bug (registering after-snapshots in `SnapshotRegistry`), emit contract fields (`verified`, `action_performed`), register `typeNotificationStateChanged`, add strict package-filtered toast tracking, unify cadence & clamp `poll_interval_ms` to `[50, 200]`, implement order-independent `nodes_digest`, and port Maestro's spatial filters and `scrollUntilVisible`.

**Tech Stack:** Kotlin 1.9, Android API 34, Android AccessibilityService, Local Embedded ADB UID 2000, JUnit 4, JSON.

---

### Task 1: Version Bump to `1.2.6-dev.1` (versionCode 40) & CHANGELOG initialization

**Files:**
- Modify: `app/build.gradle.kts:39-44`
- Modify: `CHANGELOG.md`

**Step 1: Check build.gradle.kts and update version**
Set `versionCode = 40`, `versionName = "1.2.6-dev.1"`.
Add `## [1.2.6-dev.1] - 2026-10-23` in `CHANGELOG.md`.

**Step 2: Verify build**
Run: `gradle testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
`git add app/build.gradle.kts CHANGELOG.md && git commit -m "chore: bump version to 1.2.6-dev.1 (versionCode 40)"`

---

### Task 2: Fix Temuan #0 & #12: SnapshotRegistry Capacity Expansion & Pin-on-Failure

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/SnapshotRegistry.kt`
- Test: `app/src/test/java/com/gososmed/agent/SnapshotRegistryTest.kt`

**Step 1: Write failing test in `SnapshotRegistryTest.kt`**
- Test capacity is 8 (holds 8 items before LRU eviction).
- Test `pin(snapshotId)` stores up to 2 snapshots in a separate pin map that are protected from LRU eviction.
- Test `unpin(snapshotId)` and `getPinned(snapshotId)`.
- Test `pin` cap of 2 global snapshots (attempting to pin a 3rd evicts the oldest pin or is rejected).

**Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.SnapshotRegistryTest --no-daemon`
Expected: FAIL

**Step 3: Implement SnapshotRegistry enhancements**
- `const val CAPACITY = 8`
- `const val MAX_PINNED = 2`
- Add thread-safe `pinnedEntries = LinkedHashMap<String, Entry>()`
- In `get(snapshotId)`: look up in `entries` or `pinnedEntries`.
- Implement `pin(snapshotId: String)`: copy or move entry to `pinnedEntries`, cap at `MAX_PINNED`.
- Implement `unpin(snapshotId: String)`: remove from `pinnedEntries`.
- Implement `isPinned(snapshotId: String): Boolean`.

**Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.SnapshotRegistryTest --no-daemon`
Expected: PASS

**Step 5: Commit**
`git commit -m "feat(portal): expand SnapshotRegistry capacity to 8 and add pin-on-failure"`

---

### Task 3: Fix Temuan #0, #1, #2, #3, #4: Register After-Snapshot, Emit Verified/Action-Performed, & Policy-Governed Retries

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/NodeActionExecutor.kt`
- Modify: `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- Modify: `app/src/main/java/com/gososmed/agent/ProtocolV2.kt`
- Test: `app/src/test/java/com/gososmed/agent/NodeActionExecutorTest.kt`
- Test: `app/src/test/java/com/gososmed/agent/ProtocolV2IntegrationTest.kt`

**Step 1: Write failing tests in `NodeActionExecutorTest.kt` and `ProtocolV2IntegrationTest.kt`**
- Test `executeAsync` returns `verified = true` and `action_performed = true` on success.
- Test `executeAsync` registers `transition.afterSnapshot` into `SnapshotRegistry` upon successful transition settle.
- Test consecutive actions: Action 1 succeeds -> returns `after_snapshot_id` -> Action 2 uses `snapshot_id = after_snapshot_id` -> succeeds without `stale_snapshot` error!
- Test missing/blank `snapshot_id` in `AgentCommand.kt` returns `retryable = false`.
- Test submit action (`isSubmit = true`) failure with `screen_not_changed` returns `retryable = false`.

**Step 2: Run tests to verify they fail**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.NodeActionExecutorTest --no-daemon`
Expected: FAIL

**Step 3: Implement minimal code**
- In `NodeActionExecutor.kt`:
  - On settled transition:
    If `transition.afterSnapshot != null`:
      `SnapshotRegistry.register(transition.afterSnapshot)`
  - In `successResult`:
    `put("verified", true)`
    `put("action_performed", true)`
    `put("before_state_ref", beforeSnapshot.snapshotId)` // LEAD-DEC-05
    `put("after_snapshot_id", transition.afterSnapshot?.snapshotId)`
    `put("screen_fingerprint", transition.afterSnapshot?.screenFingerprint)`
  - In error results:
    `put("verified", false)`
    `put("action_performed", didPerformAction)`
    `put("before_state_ref", beforeSnapshot.snapshotId)`
    If `request.isSubmitAction && transition.reasonCode == ProtocolV2.ReasonCodes.SCREEN_NOT_CHANGED`:
      return `ProtocolV2.Response.error(..., retryable = false)`
- In `AgentCommand.kt`:
  - For `ACT_NODE` / `ACT_AND_VERIFY`:
    If `snapshotId.isNullOrEmpty()`:
      return `ProtocolV2.Response.error(requestId, ProtocolV2.ReasonCodes.STALE_SNAPSHOT, retryable = false, ...)`
  - Pass `isSubmitAction = isSubmit` into `NodeActionExecutor.ActionRequest`.

**Step 4: Run tests to verify they pass**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.NodeActionExecutorTest --no-daemon`
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.ProtocolV2IntegrationTest --no-daemon`
Expected: PASS

**Step 5: Commit**
`git commit -m "fix(portal): register after-snapshot in registry, emit verified/action_performed, and guard retries"`

---

### Task 4: Fix Temuan #12 & ADR-06: Cadence Unification, `poll_interval_ms` Clamping, & Pre-Delay Cancel Check

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/ProtocolV2.kt`
- Modify: `app/src/main/java/com/gososmed/agent/ScreenTransitionVerifier.kt`
- Modify: `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- Test: `app/src/test/java/com/gososmed/agent/ScreenTransitionVerifierTest.kt`

**Step 1: Write failing test in `ScreenTransitionVerifierTest.kt`**
- Test that poll interval is clamped to `[50L, 200L]`.
- Test that cancellation requested right before delay aborts immediately without sleeping.

**Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.ScreenTransitionVerifierTest --no-daemon`
Expected: FAIL

**Step 3: Implement cadence unification and clamping**
- In `ProtocolV2.kt`:
  ```kotlin
  const val DEFAULT_POLL_INTERVAL_MS = 100L
  const val MIN_POLL_INTERVAL_MS = 50L
  const val MAX_POLL_INTERVAL_MS = 200L
  fun clampPollInterval(requested: Long): Long = requested.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)
  ```
- In `ScreenTransitionVerifier.kt`:
  - Default `pollIntervalMs = ProtocolV2.DEFAULT_POLL_INTERVAL_MS`.
  - Effective interval = `ProtocolV2.clampPollInterval(pollIntervalMs)`.
  - Check `if (isCancelled()) return ...` immediately before `sleeper(...)` / `delay(...)`.
- In `AgentCommand.kt`:
  - `WAIT_FOR_NODE`: `val pollIntervalMs = ProtocolV2.clampPollInterval(args.optLong("poll_interval_ms", ProtocolV2.DEFAULT_POLL_INTERVAL_MS))`
  - `WAIT_FOR_SCREEN`: `val pollIntervalMs = ProtocolV2.clampPollInterval(args.optLong("poll_interval_ms", ProtocolV2.DEFAULT_POLL_INTERVAL_MS))`

**Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.ScreenTransitionVerifierTest --no-daemon`
Expected: PASS

**Step 5: Commit**
`git commit -m "fix(portal): unify cadence to 100ms, clamp poll interval [50, 200]ms, and check cancel pre-delay"`

---

### Task 5: Fix Temuan #14, ADR-05, & §2.8/§2.10.j.3: `nodes_digest`, `node_count`, and `truncated`

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/SnapshotModels.kt`
- Modify: `app/src/main/java/com/gososmed/agent/SnapshotCollector.kt`
- Modify: `app/src/main/java/com/gososmed/agent/WindowRootCollector.kt`
- Test: `app/src/test/java/com/gososmed/agent/SnapshotCollectorTest.kt`

**Step 1: Write failing tests in `SnapshotCollectorTest.kt`**
- Test 1 (Bounds sensitive): Two nodes with identical `(resourceId, className, text)` but different bounds produce different `nodes_digest`.
- Test 2 (Text sensitive): Modifying text changes `nodes_digest`.
- Test 3 (Null vs empty text): Empty text `""` and `null` produce distinct digests without collision.
- Test 4 (Order independence): Shuffling the input nodes list produces the exact same `nodes_digest`.
- Test 5 (`node_count` & `truncated`): `node_count` accurately reflects raw count before cap; `truncated = true` when `node_count > MAX_NODES`.

**Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.SnapshotCollectorTest --no-daemon`
Expected: FAIL

**Step 3: Implement nodes_digest and pre-cap node counting**
- In `SnapshotModels.kt`:
  - Add `nodesDigest: String` and `nodeCount: Int` to `SnapshotResult`.
  - Expose `"nodes_digest"` and `"node_count"` in `SnapshotResult.toJson()`.
- In `SnapshotCollector.kt`:
  - Implement `computeNodesDigest(nodes: List<SnapshotNode>): String`:
    - Sort nodes by canonical identity tuple: `(bounds.left, bounds.top, bounds.right, bounds.bottom, resourceId ?: "", className, text ?: "", contentDescription ?: "")`.
    - Note: `node_id` is completely excluded from the digest calculation.
    - Compute SHA-256 over concatenated canonical representation.
  - Track total raw nodes counted in `WindowRootCollector.NodeBudget.totalDiscoveredCount`.
  - Pass `nodeCount` and `truncated = totalDiscoveredCount > WindowRootCollector.MAX_NODES` into `SnapshotResult`.

**Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.SnapshotCollectorTest --no-daemon`
Expected: PASS

**Step 5: Commit**
`git commit -m "feat(portal): add deterministic order-independent nodes_digest and pre-cap node_count"`

---

### Task 6: Fix Temuan #5, #7 & ADR-01: Toast Event Registration & Filtered ToastTracker

**Files:**
- Modify: `app/src/main/res/xml/accessibility_service_config.xml`
- Create: `app/src/main/java/com/gososmed/agent/ToastTracker.kt`
- Modify: `app/src/main/java/com/gososmed/agent/AgentAccessibilityService.kt`
- Modify: `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- Test: `app/src/test/java/com/gososmed/agent/ToastTrackerTest.kt`

**Step 1: Write failing test in `ToastTrackerTest.kt`**
- Test toast recording: record toast event with `packageName` and `className`.
- Test toast filtering: toasts with empty `packageName` or non-toast class name are rejected.
- Test toast retrieval: query recent toast within 3500ms TTL.
- Test toast expiration: after 3500ms, toast is expired and not returned.
- Test virtual node conversion: `ToastTracker.asVirtualNode()` returns `SnapshotNode` with `source: "toast"`, `clickable = false`, `non_authoritative = true`.
- Test stop/reset: clears toast cache.

**Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.ToastTrackerTest --no-daemon`
Expected: FAIL

**Step 3: Implement ToastTracker & A11y config update**
- In `accessibility_service_config.xml`:
  Add `typeNotificationStateChanged` to `android:accessibilityEventTypes`.
- Create `ToastTracker.kt`:
  - `data class ToastRecord(val packageName: String, val text: String, val timestampMs: Long)`
  - TTL = 3500L
  - `recordToast(event: AccessibilityEvent, nowMs: Long)`
  - `getRecentToast(targetPackage: String?, nowMs: Long): ToastRecord?`
  - `asVirtualNode(toast: ToastRecord): SnapshotNode` with `ancestorPath = "toast"`
- In `AgentAccessibilityService.kt`:
  - In `onAccessibilityEvent(event)`:
    If `event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED`:
      Call `ToastTracker.record(event)`.
- In `AgentCommand.kt` (`WAIT_FOR_NODE`):
  - If resolved node has `ancestorPath == "toast"` or `className == "android.widget.Toast"`:
    Emit `put("non_authoritative", true)`.

**Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.ToastTrackerTest --no-daemon`
Expected: PASS

**Step 5: Commit**
`git commit -m "feat(a11y): register typeNotificationStateChanged and implement package-filtered ToastTracker"`

---

### Task 7: Maestro Parity 1 - Spatial Filtering (`below`, `above`, `leftOf`, `rightOf`)

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/NodeSelector.kt`
- Modify: `app/src/main/java/com/gososmed/agent/DeterministicResolver.kt`
- Test: `app/src/test/java/com/gososmed/agent/DeterministicResolverTest.kt`

**Step 1: Write failing test in `DeterministicResolverTest.kt`**
- Create snapshot layout with an anchor node (e.g. text "Username") and several buttons ("Follow" to the right, "Block" below).
- Test query with `rightOf: "Username"` correctly resolves to "Follow" button.
- Test query with `below: "Username"` correctly resolves to "Block" button.
- Test Euclidean distance weighting (closest candidate wins).

**Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.DeterministicResolverTest --no-daemon`
Expected: FAIL

**Step 3: Implement Spatial Filtering**
- In `NodeSelector.kt`:
  - Add spatial fields to `SelectorQuery`:
    `val below: String? = null` (anchor text or id)
    `val above: String? = null`
    `val leftOf: String? = null`
    `val rightOf: String? = null`
    `val maxDistanceDp: Int = 120`
- In `DeterministicResolver.kt`:
  - Add Tier 5.5 / Spatial Evaluator:
    If any spatial constraint is set:
      Find reference node(s) matching the anchor string (text or resourceId).
      Calculate geometric bounds relationship (e.g., for `rightOf`: `node.bounds.left >= ref.bounds.right && abs(node.bounds.centerY - ref.bounds.centerY) < tolerance`).
      Award significant score boost (e.g. +70) scaled inversely by distance.

**Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.DeterministicResolverTest --no-daemon`
Expected: PASS

**Step 5: Commit**
`git commit -m "feat(resolver): implement Maestro-parity spatial filtering (below, above, leftOf, rightOf)"`

---

### Task 8: Maestro Parity 2 & 3 - Native `scrollUntilVisible` & Stale A11y Cache Refresh

**Files:**
- Modify: `app/src/main/java/com/gososmed/agent/AgentAccessibilityService.kt`
- Modify: `app/src/main/java/com/gososmed/agent/NodeActionExecutor.kt`
- Modify: `app/src/main/java/com/gososmed/agent/AgentCommand.kt`
- Modify: `app/src/main/java/com/gososmed/agent/ProtocolV2.kt`
- Test: `app/src/test/java/com/gososmed/agent/NodeActionExecutorTest.kt`

**Step 1: Write failing test in `NodeActionExecutorTest.kt`**
- Test `scrollUntilVisible` mock:
  - First 2 scrolls: target node is below viewport or not found.
  - 3rd scroll: target node appears inside safe viewport (15% - 85% of screen height).
  - Verify action settles and returns found target.
- Test `scrollUntilVisible` reaches `maxScrolls` without finding target -> returns `TIMEOUT` / `NO_NODE`.

**Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.NodeActionExecutorTest --no-daemon`
Expected: FAIL

**Step 3: Implement scrollUntilVisible & refreshAccessibilityCache**
- In `AgentAccessibilityService.kt`:
  - Add `fun refreshAccessibilityCache()`:
    ```kotlin
    fun refreshAccessibilityCache() {
        try {
            val info = serviceInfo
            serviceInfo = info
        } catch (_: Throwable) {}
    }
    ```
- In `NodeActionExecutor.kt`:
  - Add `ACTION_SCROLL_UNTIL_VISIBLE = 0x00000080` (or constant).
  - Implement `scrollUntilVisibleAsync(request, dispatcher, snapshotProvider, timeSource)`:
    Loop up to `maxScrolls` (default 8):
      Capture current snapshot.
      Resolve selector.
      If found and bounds within safe screen viewport (15% - 85% of display height), return success.
      Otherwise, dispatch swipe in direction (down/up).
      Delay 300ms for settling.
- In `AgentCommand.kt`:
  - Add command support or action argument support for `scrollUntilVisible`.

**Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.gososmed.agent.NodeActionExecutorTest --no-daemon`
Expected: PASS

**Step 5: Commit**
`git commit -m "feat(action): implement native scrollUntilVisible and a11y cache refresh"`

---

### Task 9: Full Suite Verification & Real Device Validation

**Files:**
- Verify all unit test suites (`gradle testDebugUnitTest --no-daemon`)
- Compile APK debug (`gradle assembleDebug --no-daemon`)
- Verify signature and manifest sanity.
- If device is online (`2c76b8a3`), install APK debug and smoke-test live accessibility commands via adb.

**Step 1: Run all JVM unit tests**
Run: `gradle testDebugUnitTest --no-daemon`
Expected: All 209+ tests pass with 0 failures.

**Step 2: Assemble Debug APK**
Run: `gradle assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL, artifact produced at `app/build/outputs/apk/debug/app-debug.apk`.

**Step 3: Verify APK on live device (Serial: 2c76b8a3)**
- Check `adb get-state`.
- Install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Test accessibility service and observe response.

**Step 4: Update Documentation**
- Update `CHANGELOG.md` with complete verified release notes for `v1.2.6-dev.1`.
- Update `README.md` if necessary.

**Step 5: Commit**
`git commit -m "docs: finalize v1.2.6-dev.1 changelog and verification reports"`
