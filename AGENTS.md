# GoSosmed Agent (APK) — AGENTS.md (Agent Constitution)

> **Single source of truth for ALL coding agents working in THIS repository** (DeepSeek Harness, Claude Code, WorkBuddy, Codex, Gemini CLI).
> This is the **PUBLIC** Android repo. Read this before touching anything.
> Last Updated: 2026-09-14 · Current version: see `app/build.gradle.kts` (do not trust a number written in prose anywhere).

---

## 1. What this repo is — and what it is not

| | |
|---|---|
| **Repo** | `https://github.com/dedy45/gososmed-mobile-agent` — **PUBLIC**, Apache-2.0 |
| **What it is** | Android Kotlin agent app ("BYOD Local Device Bridge"). Runs on the user's own phone, connects **outbound** over WebSocket to the GoSosmed backend, and executes UI automation via AccessibilityService. |
| **What it is NOT** | Not the backend. Not the web app. Not the database. Those live in a **separate private monorepo**: `C:\Users\dedy\Documents\Go-sosmed`. |
| **Local path** | `C:\Users\dedy\Documents\gososmed-mobile-agent` |
| **Why it is public** | It requests **AccessibilityService** — the strongest Android permission, able to read any screen and act on the user's behalf. Asking for that trust with an unauditable binary is not acceptable. Keep the code auditable. |

### 1.1 Boundary rule (hard)

**Never edit the private monorepo from a session in this repo, and never edit this repo from a monorepo session.** They have separate constitutions, separate release policies, and separate CI budgets.

**One repo = one active agent.** Do not run two agents on this repo concurrently.

The only coupling between the repos is the **version handshake**: this app reports its version over WebSocket, and the server compares it against `GOSOSMED_AGENT_LATEST_VERSION`. When this app ships a **stable** release, that server env var must be updated in the same maintenance window — that is a **server-side** task, not a change in this repo.

---

## 2. Release discipline (the most important section)

Two channels already exist via git tags in `.github/workflows/release.yml`. **The tooling is correct; the discipline is what breaks.**

| Tag | Channel | `prerelease` | Audience |
|---|---|---|---|
| `vX.Y.Z` | **stable** | false | End users |
| `vX.Y.Z-dev.N` | **dev** | true | Testers / yourself |

`build.yml` runs on every push to `main` and produces a debug artifact — that is the continuous dev build. `release.yml` runs on tag push, builds a **signed** release APK, verifies the signature with `apksigner`, emits `SHA256SUMS.txt`, and pulls release notes from `CHANGELOG.md`.

### 2.1 Gate before tagging stable — MANDATORY

All four must be true. There is no automation for #3 yet, so it is your responsibility:

1. CI green.
2. `apksigner verify` passes (automatic in the workflow — but an unsigned APK must FAIL the release, never ship silently).
3. **Tested on at least one real phone**, with the result written into `CHANGELOG.md`.
4. The honest "limits" note in `CHANGELOG.md` is filled in — what was verified, what was not.

> **If it has not been tested on a real device, the tag MUST be `-dev`.**

### 2.2 Incident that produced this rule — 2026-09-13

Eight **stable** tags shipped in 24 hours (v0.9.0 at 16:57 → v0.9.7 the next morning at 08:24). The `-dev` channel had been used exactly **once in the entire project history** (`v0.5.0-dev.1`). Each release fixed a defect in the previous one, and none had been tested on a phone — yet all reached users labelled "stable".

Read that as the failure mode to avoid: **rapid-fire stable tagging turns users into your test bench.** When you are iterating on a fix you cannot verify locally, tag `-dev`, verify on the device, and only then promote to stable.

---

## 3. Build, test, and verify

```bash
# Unit tests + debug APK
gradle testDebugUnitTest assembleDebug

# Signed release (requires keystore env — see below)
gradle assembleRelease

# Verify signature
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs app/build/outputs/apk/release/*.apk
```

> **Local toolchain is preinstalled** at `C:\Users\dedy\tools` (JDK 17, Gradle 8.9, SDK 34),
> matching CI exactly. This repo has **no** Gradle wrapper — never call `./gradlew`.
> See [`docs/TOOLCHAIN-LOKAL.md`](docs/TOOLCHAIN-LOKAL.md) for the environment setup.

**Test files live in `app/src/test/java/com/gososmed/agent/`** (`AgentCommandTest`, `HierarchySerializerTest`, `privileged/AdbShellOutputTest`, `privileged/AdbTlsProviderTest`, `privileged/PairingDialogParserTest`). Add a regression test for every behavioural fix. Unit tests run on the JVM — they **cannot** prove device behaviour; say so explicitly in your report.

**Keystore:** never commit it. It is decoded at release time from GitHub Secrets (`GOSOSMED_KEYSTORE_B64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`) into `RUNNER_TEMP`. The workflow fails hard if the secrets are missing, so an unsigned APK can never be published silently. Do not weaken that check.

---

## 4. Hard rules

- ❌ **NO secrets in the repo.** No keystore, no tokens, no signing passwords.
- ❌ **NO committing `*.apk`, `*.jks`, `*.keystore`, or build output.**
- ❌ **NO claiming "fixed" without device evidence.** Compilation + unit tests passing is not device verification. State the gap.
- ❌ **NO stable tag for an untested build** (see §2).
- ❌ **NO editing the private monorepo** (`C:\Users\dedy\Documents\Go-sosmed`).
- ❌ **NO force push, NO `--no-verify`.**
- ❌ **NO silent `return` on a failed permission/state check.** If an overlay cannot be shown or a precondition is unmet, surface it in the UI. Silent returns produced the "button does nothing" class of bug that took multiple releases to find.
- ❌ **NO unguarded code in `onStartCommand()`.** See §5.1 — an uncaught exception there kills the whole process, including the AccessibilityService.

---

## 5. Architecture facts that cause real bugs

These were each discovered the hard way. Do not re-learn them.

### 5.1 One process — a crash anywhere kills AccessibilityService

`AdbPairingService`, `AgentForegroundService`, `AgentAccessibilityService`, and `MainActivity` all live in the **same process**. A single uncaught exception in `onStartCommand()` takes down the entire process, and the AccessibilityService dies with it. Symptom observed: "Step 1 status goes dead the moment I press the Step 3 button."

**Rule:** every entry point — notification building, view construction, service startup — must be wrapped in try/catch, and the crash must be recorded to disk and shown in the Log tab.

### 5.2 Overlay: use `TYPE_ACCESSIBILITY_OVERLAY`

`TYPE_APPLICATION_OVERLAY` requires the `SYSTEM_ALERT_WINDOW` permission **plus** a separate OEM toggle (MIUI/HyperOS gate it independently), and code that silently returns when the permission is absent produces an invisible failure.

**`TYPE_ACCESSIBILITY_OVERLAY` needs no permission at all** and is not subject to OEM background-launch restrictions, because the window belongs to a system service. Obtain the `WindowManager` from the **AccessibilityService context** (not the Application context) and call `addView` on the main thread.

### 5.3 Touch listeners must be on leaf views

`ViewGroup.dispatchTouchEvent` delivers events to the **child** under the finger. A listener attached to a parent `LinearLayout` never fires when the finger lands on an `EditText` or `Button` — i.e. almost the entire card. Attach drag listeners to a **leaf view** (e.g. the title `TextView`).

### 5.4 Read accessibility state from the API, not from Settings strings

`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` has an OEM-dependent format, and the `bound` flag is lost when the OEM restarts the process. Use `AccessibilityManager.getEnabledAccessibilityServiceList()`, which returns parsed `ComponentName`s, with the Settings string only as a fallback. Treat "either source says enabled" as enabled.

### 5.5 Kotlin/Android API gotchas

- `AccessibilityServiceInfo` is in **`android.accessibilityservice`**, NOT `android.view.accessibility`. Wrong package → `Unresolved reference`.
- `view.windowManager` does **not** resolve in Kotlin. Pass a `WindowManager` explicitly as a parameter.
- Prefer comparing against a production implementation that uses the same library when a platform API misbehaves. Comparing our manifest against **AppManager** (written by the author of `libadb-android`, the library this app uses) proved our manifest was already correct and moved the search into our own code — that saved hours. Use `MuntashirAkon/AppManager` → `adb/AdbPairingService.java` as the authoritative reference.

---

## 6. Definition of done for a task

1. `gradle testDebugUnitTest assembleDebug` succeeds; new behaviour has a regression test where testable on the JVM.
2. `CHANGELOG.md` updated under `[Unreleased]` (or the version section when releasing).
3. Honest report to the human: **what changed**, **what was verified**, and **what remains unverified** (in particular: whether it was tested on a real device).
4. Exactly one commit, pushed. Tag only if it meets §2.1.
5. `README.md` version reference updated if it states a version in prose.

---

## 7. Documentation map

| File | Contents |
|---|---|
| `README.md` | Public-facing: why the repo is open, setup, release channels, feature checklist |
| `CHANGELOG.md` | Keep a Changelog + SemVer. **Release notes are generated from this** by `release.yml` — keep the `## [X.Y.Z]` heading format exact |
| `docs/PAIRING-FLOW.md` | Pairing flow design |
| `docs/TRANSPORT-ADB-LOKAL.md` | Local ADB transport |
| `docs/SHIZUKU-TRANSPORT.md` | Legacy Shizuku transport (deprecated path) |
| `docs/AGENT-COMMAND-CONTRACT.md` | **The wire contract** — which commands the agent accepts and what data it sends to the server. Keep this in sync with `AgentWsClient.kt` |
| `docs/DB-AND-API-MAP.md` | How this app maps onto backend tables/endpoints |
| `docs/SETUP-3-LANGKAH.md` | User-facing 3-step setup guide |
| `docs/F0-LIBRARY-VALIDATION.md` | Library validation notes |
