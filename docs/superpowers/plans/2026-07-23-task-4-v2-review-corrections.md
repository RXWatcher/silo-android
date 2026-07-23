# Android Mobile Task 4 V2 Review Corrections Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Task 4 v2 re-review finding while keeping manager, lifecycle, mobile UI, reducer state, and persisted track preferences aligned.

**Architecture:** Make the playback manager's active-attempt swap the final fallible commit point and move old-session cleanup to contained asynchronous work. Keep a local/downloaded choice in the adapter's effective serialized reducer state whenever audio or quality work is pending, and require post-adoption Media3 confirmation without reducing queued events from stale state. Centralize typed/legacy preference mapping in shared code so player and detail screens read both formats but write only the typed format.

**Tech Stack:** Kotlin 2.1, coroutines and coroutine-test, Media3, Kotlin serialization, JUnit/Kotlin test, Gradle Android unit tests.

## Global Constraints

- Strict TDD: add each regression and observe the expected failure before editing production code.
- Do not touch TV, server, SiloCast, observability, workflows, CI, or device code.
- Do not start Task 5.
- Commit with `[skip ci]`.
- Update `.superpowers/sdd/task-4-report.md`.

---

### Task 1: Make staged manager commit atomic

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt`
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerStagedReplanTest.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`

**Interfaces:**
- Produces: `commitStagedVideoReplan(staged)` that cannot suspend or fail after `activeVideoAttempt.set(next)`.
- Consumes: existing adapter adoption contract, which may trust `ApiResult.Success` to mean the manager owns the candidate session.

- [x] Add a manager test whose DELETE handler suspends, cancel the caller after the active attempt swaps, and assert the returned commit/adopt path still exposes the candidate session as authoritative.
- [x] Add a manager test whose DELETE handler throws and assert commit still returns `ApiResult.Success`, the candidate stays active, and cleanup failure cannot revert ownership.
- [x] Run `PlaybackSessionManagerStagedReplanTest` and confirm RED at the post-swap cleanup suspension/failure boundary.
- [x] Replace inline post-swap `stopPlayback` with contained manager-owned cleanup:

```kotlin
activeVideoAttempt.set(next)
scheduleCommittedSessionCleanup(active.sessionId, next.sessionId)
return ApiResult.Success(staged.candidate)
```

- [x] Keep cleanup exceptions/cancellation inside the cleanup coroutine so they cannot escape the committed result.
- [x] Run the manager and adapter adoption suites and confirm GREEN.

### Task 2: Serialize local mount and audio preference ordering

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/SubtitleTransition.kt`
- Test: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/SubtitleTransitionTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`

**Interfaces:**
- Produces: local/downloaded reducer intent that stays pending when an audio/quality preference is already pending.
- Produces: adapter local-mount ownership whose proposed reducer state is the base for later mutations.

- [x] Add RED reducer tests proving `UpdateAudioPreference(7)` followed by `SelectSubtitle(Downloaded/LocalMedia3)` retains a pending local identity with audio `7`.
- [x] Add RED adapter matrices for Audio→Local and Local→Audio:
  - before local mount confirmation;
  - while a staged request is outstanding; and
  - while committed playback adoption is suspended.
- [x] In every matrix, assert the final staged request uses `subtitleTrackIndex == -1`, candidate audio matches `7`, and final UI snapshot/persistence contain the same audio and subtitle identity.
- [x] Change local reducer selection to stage rather than synchronously commit when an explicit audio/quality mutation is already pending:

```kotlin
if (identity.requiresClientMount() && pending.hasServerPreferenceMutation()) {
    return stageLatest(identity, effectiveAudioTrackIndex(), effectiveQualityPreference(), ...)
}
```

- [x] When a local mount owner exists, reduce later preference events against its proposed state; never clear it merely because audio changes.
- [x] On pre-adoption mount success with server work pending, retain ownership without persisting; after adoption, issue a fresh bounded remount owner.
- [x] Replay adoption-queued events from the validated state and install the folded state directly, never by re-calling `applySelection` against stale `transition`.
- [x] Run shared reducer and mobile adapter suites and confirm GREEN.

### Task 3: Unify player/detail preference migration

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/playback/TrackSelectionFingerprint.kt`
- Test: `shared/src/commonTest/kotlin/org/siloserver/silo/playback/TrackSelectionFingerprintTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailViewModel.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailTrackSelectionSourceTest.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModelSharedCoordinatorTest.kt`

**Interfaces:**
- Produces: shared typed-first/legacy-fallback catalog preference encode/resolve helpers.
- Consumes: `combinedSubtitleSelectionIndexes(catalogTracks)` for server selection identity.

- [x] Add RED shared tests for player-write/detail-read Off and server choice, detail-write/player-read, embedded/external combined indexes, and legacy fallback.
- [x] Add a RED detail integration/source test proving an audio-only update never writes or clears subtitle preference.
- [x] Encode detail choices with `encodeSubtitleIdentityPreference(...)`; map catalog ordinals to combined server indexes rather than catalog demux indexes.
- [x] Resolve detail preferences typed-first and fall back to legacy fingerprints during migration.
- [x] Split detail persistence by dimension so audio actions only write audio and subtitle actions only write subtitle.
- [x] Keep the player typed-first with legacy mounted-fingerprint fallback, using the same shared preference decoder.
- [x] Run shared preference and mobile detail/player integration suites and confirm GREEN.

### Task 4: Preserve uncertain HI metadata and authoritative download identity

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelection.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelectionTest.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`

**Interfaces:**
- Produces: `hearingImpaired == true` only for positive label evidence; otherwise `null`.
- Produces: `SubtitleIdentity.Downloaded` whenever `downloadId != null`, independently of mutable source strings.

- [x] Add RED tests showing a generic-label catalog row has unknown HI metadata and can uniquely match an authoritative HI Media3 track, while indistinguishable duplicate rows remain ambiguous.
- [x] Add a RED end-to-end adapter test for a modern downloaded row with `downloadId` but no source/catalogSource; assert `Downloaded` identity and staged server subtitle index `-1`.
- [x] Change identity construction to:

```kotlin
hearingImpaired = subtitleLabelIndicatesHearingImpaired(label).takeIf { it }
downloaded = subtitle.downloadId != null || legacySourceIndicatesDownloaded
```

- [x] Run mobile identity, resolver, and adapter suites and confirm GREEN.

### Task 5: Full verification and handoff

**Files:**
- Update: `.superpowers/sdd/task-4-report.md`
- Update: `docs/superpowers/plans/2026-07-23-task-4-v2-review-corrections.md`

- [x] Run focused manager, reducer, preference, identity, adapter, detail, and player tests.
- [x] Run:

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  --no-parallel
```

- [x] Parse XML counts and confirm zero failures/errors.
- [x] Run `git diff --check` and audit changed paths against the global scope exclusions.
- [x] Update the Task 4 report with RED/GREEN evidence, exact counts, changed paths, commit SHA, and residual risks.
- [x] Commit production/tests with `[skip ci]`, then commit tracked plan/reporting changes with `[skip ci]`.
