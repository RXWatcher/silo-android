# Task 5 Android TV Transactional Subtitle Acceptance Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the Tasks 1–4 transactional subtitle contract into Android
TV without duplicating the shared reducer, staged-session manager, typed mount
resolver, or atomic track-selection persistence.

**Architecture:** `TvSubtitleTransactionAdapter` is a TV effect executor around
the existing `SubtitleTransitionState` and `reduceSubtitleTransition`. It
adapts TV intent, route, refresh, remount, HUD, and Media3 callbacks to the
shared reducer and the existing staged `PlaybackSessionManager` APIs. The
ViewModel exposes one adapter snapshot; the screen/HUD render it and apply only
owned typed mount requests.

**Tech Stack:** Kotlin, coroutines/StateFlow, kotlinx-coroutines-test,
AndroidX Media3-facing track snapshots, existing shared/android-shared playback
contracts, kotlin.test/JUnit, Gradle Android unit tests.

## Global constraints

- Start from clean Task 4 HEAD
  `52336f220d95e9803d7f55d0469160e2c4353d02`.
- Strict RED → GREEN → REFACTOR. No TV production edit is allowed until every
  named RED test below exists, the focused RED command has been observed, and
  the baseline auditor has reported.
- Scope is `androidTvApp`, this plan, the ignored Task 5 evidence report, and
  narrowly required `android-shared` manager/lifecycle production and test
  changes that make deferred playback publication jointly correct.
- Do not modify Android mobile, shared, server, SiloCast, observability,
  workflows, or CI. `android-shared` changes are limited to publication
  ownership, joint manager/lifecycle confirm or rollback, settlement-aware
  reset/stop, and the directly corresponding tests.
- Reuse Tasks 1–4 behavior. Do not fork a second subtitle state machine,
  metadata matcher, codec/language normalizer, session manager, or atomic Room
  writer.
- Keep the committed subtitle mounted and checked until the exact replacement
  is staged, validated, committed, adopted, remounted, and confirmed.
- No device, push, APK assembly/install, launch, or remote-build action.
- One integrated `[skip ci]` TV/publication-settlement commit only after full
  TV/shared gates and an independent review with no Critical or Important
  findings.

## Design boundary

### TV adapter

The adapter owns one `SubtitleTransitionState` plus TV execution ownership:

- content/file/session identity;
- output-route generation;
- subtitle intent generation;
- refresh generation and source;
- staged candidate/commit/adoption ownership;
- typed local/remount confirmation and meaningful-snapshot bound; and
- serialized, acknowledged persistence through the Task 4 atomic port.

It exposes a read-only snapshot containing committed and pending identities,
audio/quality intent, Applying/failure state, and the typed mount request. The
adapter never stores a display label as identity.

### ViewModel and session lifecycle

The ViewModel converts catalog, mounted Media3, audio, quality, output-route,
refresh, and lifecycle callbacks into adapter commands. Server-backed changes
use `stageActiveVideoSessionReplan`, validate the exact requested track/mode and
artifact, then call `commitStagedVideoReplan`. Playback/session state is adopted
before the adapter publishes committed state. Superseded candidates and
committed-but-unpublished sessions are settled as one manager/lifecycle
transition before their owner is cleared. A superseding subtitle, quality,
route, reset/load, exit, or clear must confirm or roll back that exact
publication before another manager request can proceed.

### Remount and refresh

Remount ownership carries `SubtitleIdentity`, not a server index or label. A
matching meaningful Media3 snapshot emits one selection request; repeated,
empty, or transitional snapshots do not consume the bound. A superseding
intent/reset invalidates the owner.

Refresh ownership contains content, file, session, intent generation, refresh
generation, and a source (`Download`, `AiCompletion`, `Realtime`). Only the
latest owner may merge rows, bump the nonce, or auto-select. Downloaded URLs are
rebased to the owner's active/staged session.

### HUD and focus

The HUD derives rows from catalog/mounted choices plus the adapter snapshot.
The committed row remains selected, the pending row renders `Applying…`, and a
failure clears only the pending marker. Selecting a row while the HUD/picker is
open does not optimistically check it, close the parent HUD, or move focus to a
different row; D-pad focus remains independent from committed selection.

## Frozen whole-lifecycle acceptance matrix

Legend:

- `Axx` — mobile-parity adapter test listed below.
- `Txx` — TV-only test listed below.
- `E:` — existing Tasks 1–4/shared coverage reused unchanged.
- `—` — structurally inapplicable.

| Operation | Success | Failure | Cancellation | Stale result | Restart/reset | Exit/clear | Catalog reorder / ambiguity |
|---|---|---|---|---|---|---|---|
| Server sidecar / burn-in | A03, A25, A27 | A26 | A13–A15 | A04, A36 | A34–A37 | A45–A47 | T02, T03; E: shared typed catalog matcher |
| Off while replacement applies | A24 | A26 | A15 | A04 | A34 | A45 | — Off has no catalog row |
| Downloaded / LocalMedia3 / Embedded | A06, A20, A28 | A07–A12, A29–A31 | A13–A15 | A19, A33 | A32, A34 | A45–A47 | A28–A29; unique `downloadId`; typed metadata ambiguity-safe |
| Audio-only with committed local subtitle | A21, A22 | A23 | A14 | A38 | A34–A35 | A45 | — audio uses its own stable fingerprint |
| Subtitle ↔ audio combined | A05–A06, A16–A20, A39–A40 | A07–A12 | A13–A15 | A04, A36, A38 | A34–A37 | A45 | Typed subtitle half uses shared resolver |
| Subtitle ↔ quality combined | T04, T05 | T06 | T07 | T08 | T09 | T10 | Shared typed subtitle half |
| Subtitle ↔ output route combined | T11, T12 | T13 | T14 | T15 | T16 | T17 | Route generation is not a catalog identity |
| Persistence | A02, A03, A28 | A41, A43 | A42, A45–A47 | A02, A44 | A34, A37 | A45–A47 | Encoded typed identity survives reorder |
| Fresh persisted restore | T18 | T19 | T20 | T21 | T22 | T23 | T02–T03 and shared matcher |
| Content/file/quality load | E: current TV load generation | T06, T13 | T09, T16 | T08, T15, T21 | T09, T16, T22 | T10, T17, T23 | Current owner only |
| Subtitle acquisition refresh | T24–T26 | T27 | T28 | T29–T31 | T32 | T33 | T34 downloaded ID ambiguity |
| Typed remount after adoption/refresh | T35, T36 | T37 | T38 | T39 | T40 | T41 | T02, T03, T42 |
| HUD row state | T43 | T44 | T45 | T46 | T47 | T48 | T02, T03 |
| HUD/picker focus and controls-open selection | T49, T50 | T51 | T52 | T53 | T54 | — UI disappears on exit | T55 |
| Replaced/candidate session cleanup | E: Task 4 manager orphan ledger and discard tests | E | E | E | E | E | — no catalog identity |
| Lifecycle publication / fresh load | T56–T59 | T60, T62 | T61–T62 | T56, T58–T64 | T60, T63–T64 | T61 | T59 |
| Deferred manager/lifecycle publication | T94, T97–T102 | T107 | T96, T106 | T97, T107 | T95, T103–T104 | T96, T105–T106, T108 | — no catalog identity |
| Exact mount callback ownership | T67–T70 | T65–T66 | T38 | T39 | T40 | T41 | T65–T68 |
| Cross-family load/replan/refresh | T63–T64, T73 | T90 | T74–T76 | T73–T76 | T74–T76 | T89 | Shared typed identity |
| TV detail preference compatibility | T77–T80 | T88 | — | T78 | T81–T87 | T89 | T78, T88 |
| Remote playback commands | T91–T92 | A08–A15 | A14–A15 | A04, A36 | A33–A39 | T17, T48 | Exact typed identity |

## Required mobile-parity RED inventory

`TvSubtitleTransactionAdapterTest` mirrors these 49 Task 4 adapter behaviors.
Names are intentionally identical so omissions are mechanically auditable.

- `A01 pre-playback server selection commits without staging`
- `A02 slow older preference write cannot overwrite newer commit`
- `A03 A remains committed while B stages and commits`
- `A04 A to B to C discards B and commits only latest C`
- `A05 subtitle then audio merge into one latest reducer transaction`
- `A06 audio then subtitle merge into one latest reducer transaction`
- `A07 local then audio before mount keeps one client-owned transaction`
- `A08 stage failure after early local mount clears applying owner`
- `A09 validation failure after early local mount clears applying owner`
- `A10 validation discard exception cannot skip rollback or kill worker`
- `A11 operation local stale discard cancellation is contained and worker survives`
- `A12 commit failure after early local mount clears applying owner`
- `A13 adoption failure after early local mount remounts prior committed local identity`
- `A14 operation local stage cancellation rolls back exact local owner and keeps worker alive`
- `A15 operation local commit cancellation rolls back exact local owner and keeps worker alive`
- `A16 parent cancellation stops stage worker without converting teardown into transaction failure`
- `A17 audio then local while staging restages combined client-owned transaction`
- `A18 modern downloaded row without source keeps server subtitles off during audio replan`
- `A19 local then audio while server subtitle stages retains local identity`
- `A20 queued local then audio during adoption preserves both intents`
- `A21 queued audio then local during adoption preserves both intents`
- `A22 audio change remounts committed downloaded subtitle without sending client index to server`
- `A23 audio change remounts committed local Media3 subtitle with server subtitles off`
- `A24 post-adoption local restore timeout keeps committed preference`
- `A25 A to Off keeps A mounted until Off candidate commits`
- `A26 missing sidecar and network failure retain committed selection and preference`
- `A27 burn-in candidate commits without a sidecar`
- `A28 downloaded and embedded choices persist only after mounted resolver confirms`
- `A29 settled local mount miss rolls back immediately without persistence`
- `A30 repeated and empty local mount snapshots do not exhaust retry bound`
- `A31 local mount rolls back after bounded timeout when tracks never settle`
- `A32 committed session replacement rebases downloaded rows to real session identity`
- `A33 content file version and session reset invalidates staged response`
- `A34 new selection during suspended commit is applied after committed base without stale overwrite`
- `A35 reset during suspended commit prevents old playback adoption and persistence`
- `A36 failed old commit after reset cannot poison next content commit`
- `A37 new selection stays queued until suspended playback adoption completes`
- `A38 audio change during adoption waits and stages from adopted subtitle`
- `A39 reset during suspended playback adoption invalidates stale callback and persistence`
- `A40 playback adoption exception is contained and worker remains available`
- `A41 first preference write exception is retried and later write remains FIFO`
- `A42 operation local persistence cancellation retries without killing consumer or flush`
- `A43 flush reports failure only after bounded primary and durable attempts then later succeeds`
- `A44 durable write leapfrogging another content key does not suppress older valid write`
- `A45 durable final write is bounded when persistence never completes`
- `A46 consumer shutdown fails pending ack and flush uses bounded durable fallback`
- `A47 flush is bounded when active consumer persistence never completes`
- `A48 refresh owner rejects stale response after intent and session changes`
- `A49 auto selection enters reducer only for current refresh owner`

## Required TV-only RED inventory

### Identity, quality, route, and restore

- `T01 HUD catalog selection while controls are open enters one transaction`
- `T02 same-label forced and full external rows resolve the exact typed identity`
- `T03 duplicate English forced and full PGS rows safely miss without an exact identity`
- `T04 subtitle then quality merges into one latest staged request`
- `T05 quality then subtitle merges into one latest staged request`
- `T06 failed combined quality subtitle replan retains committed HUD and quality`
- `T07 operation-local combined quality cancellation rolls back and worker survives`
- `T08 stale quality subtitle candidate cannot publish`
- `T09 reset while combined quality subtitle replan is suspended invalidates it`
- `T10 exit flush captures only the committed quality subtitle snapshot`
- `T11 subtitle then route generation merges into one latest staged request`
- `T12 route generation then subtitle merges into one latest staged request`
- `T13 failed route subtitle replan retains committed playback`
- `T14 operation-local route cancellation rolls back and worker survives`
- `T15 stale route subtitle candidate cannot publish`
- `T16 content reset while route subtitle replan is suspended invalidates it`
- `T17 exit invalidates route subtitle work`
- `T18 fresh TV playback hydrates downloaded rows before restoring downloadId`
- `T19 hydration failure does not replace the committed server subtitle`
- `T20 cancelled hydration cannot publish`
- `T21 stale hydration response cannot publish into a newer session`
- `T22 restart restores only the current content file quality owner`
- `T23 exit invalidates fresh restore and stops an unpublished ready session`

### Refresh sources

- `T24 download refresh merges and selects only the returned downloadId`
- `T25 AI completion refresh merges and selects only the returned downloadId`
- `T26 realtime refresh preserves committed identity without optimistic selection`
- `T27 refresh failure leaves rows nonce and committed identity unchanged`
- `T28 operation-local refresh cancellation leaves the worker available`
- `T29 older download refresh cannot overwrite newer manual intent`
- `T30 older AI refresh cannot overwrite newer download refresh`
- `T31 realtime refresh for an old session cannot publish`
- `T32 content reset while refresh is suspended invalidates the response`
- `T33 exit invalidates every refresh source`
- `T34 duplicate downloaded IDs safely miss during refresh auto-selection`

### Typed remount

- `T35 catalog B followed by embedded C remounts only C`
- `T36 catalog B followed by local C remounts only C`
- `T37 remount failure retains the committed typed identity and clears Applying`
- `T38 remount cancellation cannot select a superseded identity`
- `T39 stale remount callback after new intent emits no selection`
- `T40 reset while remount is pending invalidates the owner`
- `T41 exit while remount is pending emits no selection`
- `T42 repeated and empty remount snapshots do not consume the meaningful-snapshot bound`

### HUD and D-pad focus

- `T43 pending HUD state keeps committed row checked and labels pending row Applying`
- `T44 transaction failure removes Applying and keeps committed row checked`
- `T45 cancellation never flashes the pending row as committed`
- `T46 stale completion cannot change HUD selection`
- `T47 content reset removes prior pending HUD state`
- `T48 exit clears pending HUD state`
- `T49 selecting a catalog row while HUD is open keeps the HUD open`
- `T50 picker focus is independent from the checked committed row`
- `T51 failed selection keeps focus on the activated pending row`
- `T52 cancelled selection keeps the picker focus trap active`
- `T53 stale completion cannot move focus`
- `T54 refresh and remount preserve the focused stable option`
- `T55 duplicate labels use typed option IDs and never collapse focus targets`

### Baseline-audit additions

The clean-HEAD audit found lifecycle and compatibility surfaces not represented
by the initial TV inventory. These are part of the same Task 5 RED freeze and
must not be deferred.

- `T56 reset between manager commit and lifecycle publication abandons the committed session`
- `T57 adoption exception abandons replacement and the worker survives`
- `T58 superseded committed Ready never changes the lifecycle active session`
- `T59 same content file and quality loads publish the newest owner only`
- `T60 version switch while an old load is suspended stops the stale Ready session`
- `T61 exit after allocation but before publication stops the unpublished session`
- `T62 TV playback starter propagates cancellation without publishing failure`
- `T63 version load and quality replan cannot adopt out of order`
- `T64 version load and output route replan cannot adopt out of order`
- `T65 settled unique remount miss rolls back without selecting another row`
- `T66 settled ambiguous remount snapshot rolls back without label fallback`
- `T67 downloaded remount resolves the exact unique downloadId`
- `T68 server sidecar remount resolves the exact artifact trackId`
- `T69 burn-in commit completes without a mounted Media3 subtitle`
- `T70 Off emits exactly one owned disable request`
- `T71 newest authoritative empty refresh removes stale downloaded rows`
- `T72 accepted refresh rebases downloaded URLs to the owned session`
- `T73 quality intent during refresh invalidates the older refresh owner`
- `T74 version switch during stage invalidates and discards the candidate`
- `T75 version switch during commit abandons a committed unpublished session`
- `T76 version switch during adoption cannot publish the older playback`
- `T77 legacy TV detail fingerprint still restores and migrates`
- `T78 typed player preference resolves in TV detail after catalog reorder`
- `T79 typed Off preference restores in TV detail`
- `T80 atomic audio and subtitle persistence never exposes a torn pair`
- `T81 legacy persisted fresh restore migrates to typed identity`
- `T82 typed sidecar fresh restore resolves exact metadata`
- `T83 typed burn-in fresh restore does not wait for a mounted row`
- `T84 typed embedded fresh restore resolves exact metadata`
- `T85 typed downloaded fresh restore resolves unique downloadId`
- `T86 typed LocalMedia3 fresh restore resolves exact metadata`
- `T87 typed Off fresh restore disables subtitles exactly once`
- `T88 fresh restore metadata mismatch and ambiguity safely miss`
- `T89 exit invalidates load refresh mount and persistence owners together`
- `T90 failed version B load keeps version A mounted and committed`
- `T91 remote subtitle selection enters the typed transaction adapter`
- `T92 remote audio selection enters the same atomic transaction adapter`

### Authorized cross-layer publication-settlement additions

The final acceptance review exposed a manager/lifecycle ownership boundary that
cannot be corrected inside `androidTvApp` alone. These tests authorize only the
`android-shared` settlement work described in the global scope:

- `T93 joint rollback restores manager and lifecycle predecessor`
- `T94 joint confirm retains replacement in manager and lifecycle and stops predecessor`
- `T95 reset cannot enter lifecycle between manager and lifecycle confirmation`
- `T96 concurrent exit waits for rollback settlement then stops restored owner`
- `T97 new subtitle rolls back unpublished B before C stages and stale B ack is inert`
- `T98 output route replan rolls back unpublished B before the new route stages`
- `T99 quality replan rolls back unpublished Off before the new quality stages`
- `T100 subtitle queued during adoption rolls B back before replay can stage`
- `T101 quality queued during adoption rolls B back before replay can stage`
- `T102 route queued during adoption rolls B back before replay can stage`
- `T103 content reset jointly rolls back unpublished B before clearing its owner`
- `T104 new load invalidation settles B and lets the next content request proceed`
- `T105 explicit exit restores A before lifecycle stop and never persists B`
- `T106 clear contains settlement outside the cancelled adapter scope then stops A`
- `T107 owner loss after lifecycle adoption jointly rolls manager and lifecycle back to A`
- `T108 real exit and clear wire settlement before invalidation and lifecycle stop`

## Task 1: Freeze the RED contract

**Files:**

- Create:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleTransactionAdapterTest.kt`
- Create:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleHudStateTest.kt`
- Create:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleRefreshOwnershipTest.kt`
- Create:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlaybackFreshLoadOwnershipTest.kt`
- Create:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvTrackSelectionCompatibilityTest.kt`
- Modify:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/SubtitleRemountReselectionTest.kt`
- Modify:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModelSharedCoordinatorTest.kt`
- Modify:
  `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerControlsUsabilityTest.kt`

- [x] Add A01–A49 by mechanically mirroring the Task 4 adapter tests under the
  TV package and planned TV adapter boundary.
- [x] Add T01–T23 to the adapter/restore contract.
- [x] Add T24–T34 to the refresh-owner contract.
- [x] Add T35–T42 to typed remount tests.
- [x] Add T43–T55 to the pure HUD state and focus/source integration contracts.
- [x] Add baseline-audit T56–T92 to the lifecycle, exact-remount,
  compatibility, cross-family race, and remote-command contracts.
- [x] Confirm no production path differs from HEAD.
- [x] Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests org.siloserver.silo.tv.ui.screens.player.TvSubtitleTransactionAdapterTest \
  --tests org.siloserver.silo.tv.ui.screens.player.TvSubtitleHudStateTest \
  --tests org.siloserver.silo.tv.ui.screens.player.TvSubtitleRefreshOwnershipTest \
  --tests org.siloserver.silo.tv.ui.screens.player.TvPlaybackFreshLoadOwnershipTest \
  --tests org.siloserver.silo.tv.ui.screens.player.TvTrackSelectionCompatibilityTest \
  --tests org.siloserver.silo.tv.ui.screens.player.SubtitleRemountReselectionTest \
  --no-parallel
```

Expected RED: unresolved Task 5 adapter/HUD/refresh/remount ownership types and
behavior, with no production edit.

- [x] Record the baseline auditor's findings and exact RED output before any
  GREEN implementation.

## Later implementation gates

- [x] Implement one root-cause group at a time after the RED checkpoint.
- [x] Run focused GREEN after each group.
- [x] Run full gates:

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --rerun-tasks --no-parallel --console=plain
```

- [x] Parse XML counts and require zero failures, errors, and skips.
- [x] Run `git diff --check` and the exact authorized-scope audit.
- [x] Obtain an independent whole-range review with no Critical or Important
  findings.
- [x] Update the ignored Task 5 TV report with RED/GREEN evidence, exact paths,
  residual risks, and reviewer result.
- [x] Create one integrated TV `[skip ci]` commit. Do not push.

Final verified runtime evidence:

- The forced combined command completed with `BUILD SUCCESSFUL in 47s`; all 78
  actionable tasks were executed.
- Combined-run XML: `shared` 766 tests across 102 suites,
  `android-shared` 836 tests across 137 suites, and `androidTvApp` 854 tests
  across 130 suites, each with 0 failures, 0 errors, and 0 skipped.
- The deterministic staged-manager concurrency test passed 10 of 10 forced
  invocations.
- The final route-race-focused Android TV gate passed 90 of 90 tests.
- Focused final settlement corrections passed 7 of 7 F11–F13 tests, 10 of 10
  F14–F16 tests, 2 of 2 F17 tests, and 5 of 5 F18 tests.
- Two independent whole-range reviewers passed the frozen tree with zero
  Critical and zero Important findings.
- The combined runtime and independent review gates are complete. The
  integrated commit is ready for creation; no push is authorized.
