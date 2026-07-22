# Android Mobile Performance Remediation Design

## Goal

Remove the Android mobile detail and player performance failure modes found in the TV audit without changing visible UI, playback controls, subtitle behavior, navigation, download semantics, or resume accuracy.

## Evidence

The mobile source audit found five related issues:

- `PlayerViewModel` is registered as a Koin `factory` and injected with `koinInject()`, so it is remembered by Compose but is not owned or cleared by the navigation `ViewModelStore`.
- The 500 ms position ticker writes position, duration, and buffered position into the full `PlayerUiState`; the root `PlayerScreen` observes that state, causing the entire player and `AndroidView.update` to re-run on clock ticks.
- Explicit exit actions call `onExit()`, and `DisposableEffect.onDispose` calls it again. Each invocation launches an unsequenced suspend stop, while `onCleared()` blocks for a local write and tries to launch a server stop in an already-cancelled scope.
- `SimilarRail` resolves as many as twelve complete item details concurrently as soon as a movie or series detail opens.
- The series download roll-up fetches every season network-first in a navigation-owned ViewModel and continues after the detail route is covered by playback.

Mobile Home has no additional page-entry warming or shell-wide collection probing. Its older-server section fallback already uses the shared four-request concurrency cap, so no mobile-specific Home change is required.

## Approaches Considered

### 1. Tune polling and request constants only

Lengthen the position interval, reduce recommendation count, and increase the season-roll-up delay. This is small but retains incorrect lifecycle ownership, duplicate teardown, root recomposition, and background work.

### 2. Port the proven TV boundaries to mobile — selected

Make the player lifecycle-owned, isolate the playback clock, use application-owned final-position persistence plus sequenced asynchronous stop, and bind secondary detail work to route activity with bounded concurrency. This removes the causes while preserving behavior.

### 3. Rewrite the mobile player and detail aggregation layer

Split the large player into a new presentation architecture and replace detail aggregation endpoints. This could improve maintainability later, but it expands risk beyond the focused remediation.

## Selected Design

### Player lifecycle ownership

- Register `PlayerViewModel` with Koin's `viewModel` DSL rather than `factory`.
- Resolve it with `koinViewModel()` from the player navigation destination/composable so the navigation back-stack entry owns it.
- Preserve one player ViewModel for the lifetime of one player destination, including recomposition and configuration change, and guarantee `onCleared()` after the destination is removed.
- Do not alter audiobook-player ownership or unrelated injected services.

### Playback-clock isolation

- Introduce a `PlaybackClock` value containing position, duration, and buffered position.
- Derive a structural presentation state that is equal when only clock fields differ.
- Let the root player observe structural state. Observe `PlaybackClock` only in the transport/overlay subtree that renders time, seek progress, buffer progress, intro/credits timing, or next-up timing.
- Keep Media3 callbacks, seek mapping, debug clock mirroring, progress reporting, and Watch Together behavior on the live raw state where required.
- Ensure a clock-only update cannot invalidate the video `AndroidView.update` block.

### Idempotent teardown and progress durability

- Make one exit request idempotent per player destination, so explicit exit plus composition disposal cannot enqueue duplicate cleanup.
- Snapshot content id, file id, position, and duration before navigation removes the destination.
- Submit final progress through the existing application-owned `FinalPlaybackPositionWriter`, which survives ViewModel cancellation and requests outbox synchronization after the local write.
- Use `PlaybackSessionLifecycle.stopAsync()` for the network/session shutdown. Its pending-stop sequencing must complete an old stop before a new session starts.
- Remove `runBlocking` from `onCleared()` and remove server-stop launches from the cancelled `viewModelScope`.
- Navigation remains immediate and cleanup failures remain best-effort.

### Similar-item enrichment

- Keep the existing recommendation limit, ranking, cards, and empty/error behavior.
- Defer enrichment briefly so primary detail content becomes interactive first.
- Resolve full details with `mapConcurrentBounded(maxConcurrency = 3)` rather than one coroutine per recommendation.
- Run the work only while the detail destination is active. Cancellation is silent and late results from an inactive/generation-mismatched request must not publish.

### Series download roll-up

- Continue computing the same automatic “all episodes downloaded” badge while the detail route is active.
- Cancel the crawl when the route stops, including when playback covers the detail destination.
- Resume or restart the incomplete crawl when the route becomes active again, retaining any already published partial file ids.
- Keep season order, selected-season-first rendering, and the current rule that a failed season prevents a complete-series badge.

## Error and lifecycle behavior

- Cancellation caused by route changes is not displayed as an error.
- Returning from playback refreshes detail progress and resumes incomplete secondary enrichment.
- Multiple exit signals produce exactly one final-position submission and one asynchronous lifecycle stop.
- Configuration changes retain the lifecycle-owned player ViewModel and do not create a second playback session.
- Player cleanup must not stop a newer session.

## Testing

- Add a presentation-state unit test proving clock-only changes leave structural state equal while producing a new `PlaybackClock`.
- Add source/lifecycle boundary tests proving mobile uses Koin ViewModel ownership, one idempotent exit path, `FinalPlaybackPositionWriter`, `stopAsync()`, and no blocking teardown.
- Add detail tests proving recommendation hydration is capped at three and secondary work is route-active only.
- Add detail ViewModel tests or focused source-boundary tests proving the season roll-up cancels off-route and resumes on return.
- Run the focused mobile unit tests, the full `androidApp` unit suite, relevant shared and `android-shared` tests, and assemble the mobile debug APK.
- Do not install or launch an app unless the user separately requests it.

## Acceptance Criteria

- `PlayerViewModel` is navigation/ViewModelStore-owned and is cleared when its player destination is removed.
- Clock-only updates do not invalidate root player composition or `PlayerView.update`.
- One destination exit cannot enqueue more than one final write or session stop.
- Mobile player teardown contains no `runBlocking` and no post-`onCleared` launch in `viewModelScope`.
- Similar-detail hydration never exceeds three concurrent requests.
- Recommendation and season-roll-up work stops while detail is inactive and can resume on return.
- UI, playback, subtitle, download-badge, and resume behavior remain unchanged.
- All relevant tests pass and the Android mobile debug APK assembles.
