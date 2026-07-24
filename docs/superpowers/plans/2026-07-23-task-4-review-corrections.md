# Android Mobile Transactional Subtitle Review Corrections Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Task 4 review finding while preserving transactional playback, mounted subtitle identity, backward-compatible local preferences, and existing Android behavior.

**Architecture:** Keep `MobileSubtitleTransactionAdapter` as the single serializer for mobile track mutations. Extend it with owner-checked adoption, merged reducer preference events, settled mount termination, and ordered persistence; reuse the Task 3 resolver for both Media3 selection and catalog-row mapping. Persist typed subtitle identities in the existing local string column using a versioned client-only encoding with legacy fingerprint fallback.

**Tech Stack:** Kotlin, coroutines and coroutine-test, Media3, shared Kotlin serialization, Room-backed `UserItemStatePort`, Gradle Android unit tests.

## Global Constraints

- Strict TDD: every production correction must have a focused failing regression first.
- Do not start Task 5.
- Do not touch TV, server, SiloCast, observability, workflows, or device deployment.
- Commit with `[skip ci]`.
- Run full Android mobile and relevant shared/android-shared suites before completion.

---

### Task 1: Serialize manager commit through lifecycle adoption

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionLifecycle.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionLifecycleTest.kt`

**Interfaces:**
- Produces: owner-checked `MobileSubtitlePlaybackAdoption`, `MobileSubtitleAdoptionResult`, and committed-playback cleanup after invalidation/failure.

- [x] Add suspended-adoption regressions proving a newer selection remains queued, reset invalidates adoption, and callback exceptions enter recovery.
- [x] Run the focused adapter suite and confirm RED.
- [x] Keep `commitInFlight` true through a `NonCancellable` owner-checked adoption result; clean up the manager-committed session on supersession/failure and continue queued intents only after finalization.
- [x] Add lifecycle conditional adoption that checks ownership inside its mutex before mutation.
- [x] Run adapter and lifecycle suites and confirm GREEN.

### Task 2: Make catalog and Media3 resolution use one typed identity

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleMountResolver.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/backend/VideoPlaybackBackend.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/backend/Media3VideoPlaybackBackend.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/video/VideoTrackSelectionCoordinator.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelection.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Test: Task 3 resolver, manager, backend, and mobile identity suites.

**Interfaces:**
- Produces: typed `selectMountedSubtitle(identity)` and ambiguity-safe `mobileSubtitleOrdinal(identity, rows): Int?`.

- [x] Add RED regressions for extracted embedded sidecars with reserved IDs, `vtt`/`webvtt`, exact local IDs, and hearing-impaired duplicates.
- [x] Make an explicit real artifact URL/runtime source win over embedded catalog provenance.
- [x] Select pending mounted subtitles directly by `SubtitleIdentity`; never gate Media3 resolution on a lossy UI ordinal.
- [x] Map pending UI only when a unique typed catalog row resolves; represent Off only for `SubtitleIdentity.Off`.
- [x] Run focused resolver/backend/mobile suites and confirm GREEN.

### Task 3: Terminate stable mount misses

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`

**Interfaces:**
- Produces: explicit settled mount reports and a deterministic timeout owned by the adapter.

- [x] Add scheduler-driven RED tests for one settled stable nonmatch and timeout, plus controls showing empty/repeated transitional callbacks do not fail.
- [x] Clear the pending local transaction on settled nonmatch or timeout while retaining the old committed identity and persistence.
- [x] Run the focused adapter suite and confirm GREEN.

### Task 4: Merge audio preference changes into the reducer transaction

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Test: adapter and source-integration suites.

**Interfaces:**
- Produces: `selectAudio(audioTrackIndex)` using `UpdateAudioPreference`; staged candidates validate selected audio.

- [x] Add RED tests for subtitle-B then audio, audio then subtitle-B, and audio during adoption.
- [x] Generalize queued mutations so reducer events replay in order after adoption and only the latest merged state stages.
- [x] Route mobile audio selection exclusively through the adapter and persist/update UI only after commit.
- [x] Verify version changes reset the adapter and recovery replans cannot publish stale state over an owned transaction.
- [x] Run focused adapter and ViewModel integration suites and confirm GREEN.

### Task 5: Persist typed identities in commit order

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/playback/TrackSelectionFingerprint.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/playback/TrackSelectionFingerprintTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Test: shared persistence and mobile adapter suites.

**Interfaces:**
- Produces: versioned typed subtitle preference encode/decode with legacy fingerprint resolution.

- [x] Add RED round trips for Downloaded and LocalMedia3 metadata and a delayed-write ordering regression.
- [x] Encode typed identities into the existing subtitle fingerprint string column while retaining legacy reads.
- [x] Restore typed preferences directly and serialize/coalesce adapter persistence so an older slow write cannot overwrite a newer commit.
- [x] Run shared and mobile persistence suites and confirm GREEN.

### Task 6: Keep burn-in out of Media3 subtitle refresh

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModelSharedCoordinatorTest.kt`

**Interfaces:**
- Consumes: committed `SubtitleIdentity.ServerBurnIn`.
- Produces: text-track disable without subtitle MediaItem rebuild.

- [x] Add a RED source/integration regression proving burn-in calls mounted text disable and never builds a blank subtitle entry.
- [x] Publish committed typed identity to the screen and explicitly disable Media3 text tracks for burn-in.
- [x] Run the focused player integration suite and confirm GREEN.

### Task 7: Full verification and handoff

**Files:**
- Update: `.superpowers/sdd/task-4-report.md`

- [x] Run full `:androidApp:testDebugUnitTest --no-parallel`.
- [x] Run relevant `:shared:testDebugUnitTest` and `:android-shared:testDebugUnitTest` suites.
- [x] Run `git diff --check`.
- [x] Commit all corrections with `[skip ci]`.
- [x] Record RED/GREEN evidence, exact counts, commit SHA, scope audit, and remaining concerns in the Task 4 report.
