# Android TV Performance Remediation Design

## Goal

Remove multi-second Android TV UI stalls without changing the visible layout, navigation model, focus behavior, playback controls, subtitle behavior, displayed data, or resume semantics.

## Evidence

The Google TV Streamer diagnostic run recorded a 5.189-second Home frame, a 4.705-second main-thread stall, 1.3–2.1-second return-navigation frames, and repeated 300–624 ms player frames. The matching code and logcat show four interacting sources:

- Home eagerly fetches full detail and hero-sized artwork for up to sixteen items.
- Detail resolves twelve recommendations concurrently and fetches favorite state once per episode.
- the 3,226-line `TvPlayerScreen` observes the complete `UiState`, so the 500 ms position ticker recomposes the whole screen; its generated method exceeds ART's JIT instruction limit;
- player teardown synchronously waits for a Room transaction, after which the recreated shell probes every library and restarts Home warming.

## Approaches Considered

### 1. Tune constants only

Reduce preload counts, lengthen the position polling interval, and shorten transitions. This is low risk but retains the same unbounded work and oversized recomposition boundary. It would hide rather than remove the failure mode.

### 2. Bounded work plus targeted player-state isolation — selected

Warm only the currently focused card and its nearest neighbors, cancel secondary detail work when the detail route is no longer active, cap request concurrency, and move the frequently changing playback position into a narrow state consumed only by the scrubber/HUD. Persist the final position through an application-owned asynchronous writer rather than blocking `onCleared`.

This retains the current experience while directly removing the work correlated with the stalls.

### 3. Rebuild the TV presentation/data layer

Replace the current detail/home aggregation endpoints and split the entire player into a new architecture. This may eventually be desirable, but it is too broad for a focused performance repair and carries unnecessary playback and focus risk.

## Selected Design

### Home and shell

- Remove page-entry warming of the first two rows' full item details and hero artwork.
- Retain focus-driven loading and prefetch only the two neighboring cards in the currently focused row.
- Bound concurrent neighbor artwork/detail work so focus movement cannot create an accumulating queue.
- Bound the older-server Home section fallback rather than launching one request per unresolved section at once.
- Cache library collection availability for the lifetime of the shell/profile and invalidate it when the library identity set changes. A return from detail/player must not re-probe unchanged libraries.

Cards continue to show their existing ThumbHash or artwork while a focused asset loads. Layout, focus restoration, row ordering, and hero transitions remain unchanged.

### Detail

- Treat recommendations and episode favorite enrichment as secondary work.
- Resolve recommendations with bounded concurrency and cancel the work when detail leaves the active lifecycle.
- Bound episode favorite enrichment and publish one coherent map for the active episode list.
- Preserve ranking, labels, favorite actions, and all existing empty/error behavior.

### Player

- Introduce a small playback-clock state containing position and duration.
- Keep structural/session data in the existing player state, but ensure 500 ms clock updates do not invalidate the root `TvPlayerScreen` composition.
- Pass clock state only to controls that render time, progress, intro/credits, or next-up decisions.
- Keep the `PlayerView` binding/update keyed to player identity and video-fill/subtitle-bound changes, not clock ticks.
- Extract cohesive player subtrees/helpers as needed so ART can compile the hot composable paths. This is an internal boundary change only; controls and behavior stay visually identical.

### Teardown and progress durability

- Snapshot final content/file/position/duration before the player route exits.
- Submit the final Room/outbox write to an application-owned IO scope that survives ViewModel cancellation.
- Coalesce writes by content/file and retain the existing validation that rejects zero, negative, or non-finite positions.
- Never wait on Room or HTTP from `ViewModel.onCleared`.

The user must retain the same or better final resume accuracy. Failure remains best-effort and must not delay navigation.

### Diagnostics overhead

The existing `FrameMetrics` listener, memory sampler, and heartbeat remain because they run off-main except for the one-second heartbeat. Synchronous structured-log rendering is not part of the primary repair; it may be moved behind an asynchronous sink only if measurement shows material caller-thread cost and privacy/redaction-before-storage remains intact.

## Error and lifecycle behavior

- Cancelled speculative work is not surfaced as an error.
- Failed focused enrichment leaves the current lightweight card/ThumbHash intact.
- Late results must be generation-checked before changing the focused hero, episode map, or recommendation shelf.
- Profile/server/library changes invalidate scoped caches and cancel old-scope work.
- Player exit is immediate even if the final local progress write is queued.

## Testing

- Unit-test prefetch window selection, concurrency/generation behavior, Home fallback batching, and collection-availability caching.
- Unit-test clock-state reduction separately from structural player state.
- Unit-test final-position snapshot/coalescing and ViewModel teardown without a blocking call.
- Keep existing focus, detail, subtitle, and player suites green.
- Build the TV debug APK, then reproduce Home → show detail → player → detail → Home on the Google TV Streamer with detailed capture.

## Acceptance criteria

- No unbounded per-section, per-recommendation, per-episode, or hero-artwork fan-out.
- Playback position updates do not update the structural player state or invoke the `PlayerView` update block.
- No `runBlocking` remains in TV player teardown.
- Existing UI and playback behavior tests pass.
- On-device capture shows no multi-second main-thread stall attributable to Home warming, player clock recomposition, or teardown.
