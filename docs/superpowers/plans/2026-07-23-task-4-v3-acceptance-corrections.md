# Task 4 V3 Whole-Range Acceptance Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every remaining Task 4 mobile subtitle correctness gap and freeze a whole-range acceptance matrix so future review evaluates one complete lifecycle contract.

**Architecture:** Keep `SubtitleTransitionState` as the logical state machine, but give local mount ownership an explicit rollback path, session cleanup an orphan ledger, preference persistence an acknowledged atomic writer, persisted server identities stable metadata, fresh-playback restoration a download hydration step, and player loads a generation owner. Each boundary remains Android mobile/shared-client only and is driven by behavior-level tests.

**Tech Stack:** Kotlin, coroutines, kotlinx-coroutines-test, Media3-facing mobile state, shared Kotlin serialization, Room repository, Gradle Android unit tests.

## Global Constraints

- Strict RED → GREEN → REFACTOR: no production correction before its regression fails for the expected reason.
- No Android TV, server, SiloCast, observability, workflow, CI, or device action.
- Preserve the manager's atomic, nonblocking active-session commit point.
- Preserve legacy subtitle fingerprints while making typed preferences reorder-safe.
- Every production/test commit and documentation commit uses `[skip ci]`.
- Independent re-review determines acceptance; implementation must not claim approval.

---

## Root-cause ledger

1. `reportMountedSelection(... selected=true)` cancels the local timeout before
   server work completes, while `fail(...)` only reduces the staged transition.
   The owned `pendingLocalSelection` remains forever and masks the rolled-back
   reducer state as Applying.
2. `scheduleCommittedSessionCleanup` catches `Throwable` and discards both a
   failed `ApiResult` and an exception/cancellation. No later lifecycle call
   knows that the replaced session is orphaned.
3. `applyCoordinatorStateToUi` decodes and resolves a persisted client-owned
   identity against `playbackState.subtitleUrls` before calling
   `subtitlesRepository.list`; downloaded rows therefore do not exist on a
   fresh session.
4. The persistence consumer has no per-request exception boundary or
   acknowledgement; one throw kills it. The real port writes audio and
   subtitle separately, so a crash between calls leaves a torn logical choice.
   Exit invalidates state without awaiting the queue.
5. typed sidecar/burn-in preferences contain only a combined session index and
   catalog resolution ignores embedded metadata. Reordering can silently select
   a different track at the saved index.
6. Every `loadContent` invocation launches independently. No owner is checked
   after coordinator/catalog/settings suspension, so an older content,
   file-version, or quality load can publish last and reset the subtitle owner.

## Frozen acceptance matrix

Legend: `E:` existing test; `N:` new RED test in this plan; `—` structurally
inapplicable (with the reason stated in the cell). Every applicable cell has a
test owner before production work begins.

| Operation | Success | Failure | Cancellation | Stale result | Restart/reset | Exit/clear | Catalog reorder |
|---|---|---|---|---|---|---|---|
| Server sidecar/burn-in selection | E: `A remains committed while B stages and commits`; E: `burn-in candidate commits without a sidecar` | E: `missing sidecar and network failure retain committed selection and preference` | E: `reset during suspended commit prevents old playback adoption and persistence` | E: `A to B to C discards B and commits only latest C` | E: `content file version and session reset invalidates staged response` | E: reset-during-stage/commit/adoption invalidation contract; N:P2 adds lifecycle persistence flush | N:C1 `external typed preference follows metadata across combined-index reorder`; N:C3 `typed server preference safely misses when stable metadata no longer matches` |
| Client-owned Downloaded/LocalMedia3/Embedded mount | E: `downloaded and embedded choices persist only after mounted resolver confirms` | E: settled miss + timeout tests; N:L1–L4 cover stage/validation/commit/adoption failure after early mount success | E: reset during suspended commit/adoption tests | E: `refresh owner rejects stale response after intent and session changes` | E: `committed session replacement rebases downloaded rows to real session identity`; E: post-adoption remount tests | E: adapter invalidation tests; N:P2 verifies final persistence survives exit | N:C2 `embedded typed preference follows metadata across combined-index reorder`; N:C3 mismatch-safe miss |
| Audio-only replan with committed client-owned subtitle | E: downloaded and LocalMedia3 remount tests | E: `post-adoption local restore timeout keeps committed preference` | E: suspended adoption reset test | E: `new selection during suspended commit is applied after committed base without stale overwrite` | E: post-adoption downloaded/local remount tests | E: adapter invalidation tests; N:P2 final acknowledged write | — audio identity has its own stable fingerprint and is not a subtitle catalog row |
| Audio ↔ local combined mutation | E: phase matrix below | N:L1–L4 failure matrix below | E: reset during suspended commit/adoption | E: queued mutation/adoption tests | E: reset tests | E: invalidation tests plus N:P2 | N:C1–C3 cover the subtitle half; audio matching already has `resolvesAudioFingerprintBackToTrackOrdinal` |
| Fresh persisted restore | N:D1 `fresh playback hydrates downloaded rows before restoring persisted downloadId`; E: legacy fingerprint fallback | N:D2 `download hydration failure falls through without replacing committed server selection` | N:G2 stale/cancelled hydration cannot publish | N:G1/G2 load generation tests | N:G1 same-content version/quality restart | N:G2 exit invalidates load owner and stops stale session | N:C1–C3 |
| Preference persistence | E: slow older write FIFO test; N:P0 Room atomic logical write | N:P1 `first preference write throws then retry and later write both complete` | N:P2 `slow preference write plus exit flushes final committed choice` | E: FIFO older/newer ordering, extended by P1 | N:P3 `reset flush barrier persists only captured committed snapshot` | N:P2 and onCleared durable-final source/integration assertion | N:C1–C3 prove the payload is stable |
| Replaced-session cleanup | E: atomic nonblocking commit test | N:M1 `failed bounded cleanup is orphaned and drained by later stop` | N:M2 `cancelled cleanup is orphaned and drained by content reset` | E: stale handle/candidate ownership tests | N:M2 | N:M1 stop drain | — cleanup has no catalog identity |
| Content/file-version/quality load | E: normal shared coordinator startup and version-switch routing | Existing error/server-unreachable paths plus N:G3 `failed stale load cannot overwrite current ready state` | N:G2 `cancelled load cannot publish and a returned stale session is stopped` | N:G1 `two out-of-order content version quality loads publish only newest owner` | N:G1 includes same-content file/quality restart | N:G2 | N:C1–C3 run inside the current owner only |

## Audio ↔ local ordering by phase

This matrix is intentionally explicit because earlier reviews found gaps by
moving the same two events to a different phase.

| Phase | Local → Audio | Audio → Local |
|---|---|---|
| Before Media3 mount confirmation | E: `local then audio before mount keeps one client-owned transaction` | E: `audio then local while staging restages combined client-owned transaction` starts with audio and retains the final local identity |
| While server stage is suspended | E: `local then audio while server subtitle stages retains local identity` | E: `audio then local while staging restages combined client-owned transaction` |
| After early local mount, before validation | N:L1 stage failure; N:L2 validation failure | E: audio→local staging test plus N:L2 owner-aware rollback |
| While commit is suspended | E: `new selection during suspended commit is applied after committed base without stale overwrite`; N:L3 commit failure after early mount | E: audio→local staging and queued-adoption tests plus N:L3 rollback |
| While adoption is suspended | E: `queued local then audio during adoption preserves both intents` | E: `queued audio then local during adoption preserves both intents` |
| Adoption callback fails | N:L4 `adoption failure after early mount restores prior committed identity` | E: audio→local ordering plus N:L4 owner-aware rollback |
| Post-adoption remount | E: downloaded and LocalMedia3 audio-remount tests | E: same final-state behavior; N:P0 verifies one atomic snapshot |
| Restart/exit during any owned phase | E: reset-during-commit/adoption tests; N:P2 | E: same invalidation tests plus N:P2 final atomic flush |

## Task 1: Local owner rollback after early mount

**Files:**
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`

**Interfaces:**
- Consumes: `PendingLocalSelection`, `fail`, `finishFailedCommit`, `reportMountedSelection`.
- Produces: one terminal rollback helper that clears the exact owner and requests
  remount of the previously committed client-owned identity when necessary.

- [x] Add N:L1–L4 as minimal behavior tests. Each test must assert
  `subtitleApplying == false`, no failed preference write, the old committed
  identity, and the old identity as `localMountIdentity` only when it requires
  Media3 remount.
- [x] Run only those tests and record the expected RED state: the current
  snapshot remains Applying or omits the prior remount owner.
- [x] Route stage error, validation rejection, commit error, adoption failure,
  cancellation/reset, and queued failure through owner-aware rollback.
- [x] Use the existing `mountedBeforeAdoption` fact in that rollback decision,
  then remove it if the final API makes the fact implicit; no dead field remains.
- [x] Run the complete adapter suite and confirm GREEN.

## Task 2: Bounded orphan cleanup

**Files:**
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerStagedReplanTest.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt`

**Interfaces:**
- Consumes: post-swap cleanup scheduler, `beginContentReset`, `stopSession`.
- Produces: mutex-owned orphan IDs, a bounded asynchronous retry, and explicit
  orphan drains on reset/stop without moving the atomic commit point.

- [x] Add N:M1 and N:M2; make DELETE fail/throw/cancel before the later lifecycle drain.
- [x] Run the two tests and verify RED because the old ID is never attempted again.
- [x] Track cleanup ownership before launching, clear it only on successful
  `ApiResult.Success`, bound automatic attempts, and preserve failed/cancelled IDs.
- [x] Drain orphan IDs during later `stopSession` and `beginContentReset`, excluding
  the current protected session and retaining any ID whose drain still fails.
- [x] Run staged manager tests and confirm GREEN and nonblocking commit coverage.

## Task 3: Fresh download hydration before restore

**Files:**
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileFreshSubtitleRestoreTest.kt`
- Create: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileFreshSubtitleRestore.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`

**Interfaces:**
- Consumes: `SubtitlesRepository.list`, `mergeDownloadedSubtitles`,
  `decodeSubtitleIdentityPreference`, `resolveMobileSubtitleOrdinal`.
- Produces: a suspend preparation result containing hydrated mounted rows and a
  persisted selection resolved by authoritative `downloadId`.

- [x] Add N:D1/D2 and verify D1 RED against the current restore order.
- [x] Fetch downloaded rows after the remote session exists but before persisted
  subtitle resolution; merge them with the new session URL.
- [x] Treat hydration as best effort, but never convert a missing persisted
  client-owned identity into an optimistic wrong selection.
- [x] Integrate the hydrated list into the single current load-owner publish.
- [x] Run fresh-restore, auto-selection, and adapter tests GREEN.

## Task 4: Acknowledged atomic preference persistence

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/port/UserItemStatePort.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/data/repository/RoomUserItemStateRepository.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/data/repository/RoomUserItemStateRepositoryTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`

**Interfaces:**
- Adds compatible `UserItemStatePort.recordTrackSelection(contentId, fileId,
  audioFingerprint, subtitleFingerprint)`; the default implementation preserves
  non-Android callers, while Room overrides it with one transaction/upsert.
- Adds adapter acknowledgement/flush semantics: normal `onExit` owns and awaits
  the suspend flush, while `onCleared` only requests a contained non-blocking
  fallback on the persistence worker's independent supervised IO scope.

- [x] Add N:P0–P3 and verify RED: torn writes are possible, the worker dies on
  first exception, and exit can overtake a slow request.
- [x] Make the Room implementation update both columns in one transaction.
- [x] Serialize logical requests, contain each exception, retry with a fixed
  finite bound, acknowledge completion, and retain FIFO/latest-wins ordering.
- [x] On normal exit enqueue the captured final committed snapshot and await a
  flush before teardown; on clear request the same captured snapshot on the
  supervised persistence scope without blocking the main thread or adding a
  `runBlocking` track write.
- [x] Run Room and adapter persistence suites GREEN.

## Task 5: Reorder-safe typed identities

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/SubtitleTransition.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/playback/TrackSelectionFingerprint.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/playback/TrackSelectionFingerprintTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelection.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelectionTest.kt`

**Interfaces:**
- Server sidecar/burn-in identities carry optional stable media metadata beside
  the current session index.
- Catalog and mounted resolvers use unique stable metadata matches; they may use
  the index only when metadata is absent (legacy typed payload), and must return
  null on metadata mismatch or ambiguity.

- [x] Add N:C1–C3, including external and embedded reorder, and verify RED by
  observing wrong-index selection.
- [x] Persist label/language/codec/forced/HI metadata for all catalog identities.
- [x] Resolve unique metadata before session index and verify kind; never silently
  accept a row whose metadata conflicts with the stored identity.
- [x] Keep old typed payloads and legacy pipe fingerprints readable.
- [x] Run shared fingerprint and mobile resolver suites GREEN.

## Task 6: Player load generation ownership

**Files:**
- Create: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobilePlayerLoadOwner.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobilePlayerLoadOwnerTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModelSharedCoordinatorTest.kt`

**Interfaces:**
- Produces a generation owner containing content ID, preferred file ID, and
  quality; every UI publish, subtitle reset/adoption, and deferred restore checks
  ownership.
- A stale returned Ready session is explicitly stopped.

- [x] Add N:G1–G3 using two deferred loads completed out of order; include
  different content IDs and same-content file/quality changes.
- [x] Verify RED because the older load publishes last and its session is not stopped.
- [x] Cancel the prior load job, advance generation, and check owner before every
  post-suspension publish and subtitle adoption.
- [x] If a non-cooperative stale load returns Ready, stop its session and publish nothing.
- [x] Invalidate the owner on exit/clear.
- [x] Run load-owner and PlayerViewModel integration/source suites GREEN.

## Task 7: Whole-range verification and reporting

**Files:**
- Modify: `.superpowers/sdd/task-4-report.md` (ignored evidence record)
- Modify: this plan

- [x] Send a checkpoint after all N:L/M/D/P/C/G tests have been observed RED,
  listing exact failures and confirming no production file has changed.
- [x] Implement one root-cause group at a time and record each GREEN command.
- [x] Run:

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  --no-parallel
```

- [x] Parse module XML counts and confirm zero failures/errors/skips.
- [x] Run `git diff --check`, list exact changed paths, and audit excluded scopes.
- [x] Update the ignored Task 4 report with the frozen matrix, RED/GREEN evidence,
  exact commits/counts/paths, and residual risks.
- [x] Commit production/tests and tracked plan changes with `[skip ci]`.
- [x] Report evidence for independent re-review without claiming acceptance.

## Final integrated evidence

- All L/M/D/P/C/G regression groups were observed RED before their production
  correction, then GREEN in their focused suites. Later review discoveries were
  also driven RED first: codec/language alias symmetry, reset-during-commit
  poisoning, discard cleanup throw/cancellation containment, authoritative
  downloaded identity, and load/starter cancellation after server allocation.
- Catalog, mounted, and mobile identity paths now share canonical subtitle
  codec and primary-language families. Stable metadata matching is unique and
  ambiguity-safe; `Downloaded` restoration is authoritative only for a unique
  domain `downloadId`.
- Fresh restore hydrates downloaded rows before persisted resolution. The load
  generation owner gates every post-suspension publish, and
  `MobileVideoPlaybackStarter` stops an allocated-but-unpublished session when
  cancellation or adoption failure interrupts startup.
- Room writes audio/subtitle choice atomically. Adapter persistence is
  serialized, acknowledged, bounded, retrying, and owner-keyed; normal exit
  awaits its flush and clear requests a process-owned durable fallback.
- Session replacement preserves the atomic manager commit point. Failed old or
  discarded candidate cleanup is retained in an orphan ledger, cleanup occurs
  outside the ownership mutex, and later reset/stop drains retained IDs.
- Independent final integrated review reported no findings.
- Final verification:
  - `shared`: 766 tests, 0 failures, 0 errors, 0 skipped.
  - `android-shared`: 806 tests, 0 failures, 0 errors, 0 skipped.
  - `androidApp`: 508 tests, 0 failures, 0 errors, 0 skipped.
  - Total: 2,080 tests, 0 failures, 0 errors, 0 skipped.
  - `BUILD SUCCESSFUL`; `git diff --check` clean; excluded-scope audit clean.
- The integrated Task 4 commit contains exactly 26 tracked Android mobile,
  Android-shared, shared, test, and V3-plan paths. The ignored
  `.superpowers/sdd/task-4-report.md` evidence record is intentionally excluded.
- Residual risks are bounded and explicit: all DELETE attempts can still fail
  until server expiry; a persistently failing preference backend can defeat
  both bounded final-write paths; duplicate downloaded IDs intentionally
  require manual reselection; and physical-device validation remains outside
  this Task 4 scope.
