# Android Startup Home Fan-out Remediation Design

## Context

The Google TV Streamer diagnostics bundle captured 177 HTTP completions in roughly 84 seconds. The largest burst followed authenticated startup and included two `/api/v1/home/sections` responses plus 31 `/api/v1/home/sections/{id}/items` responses. During the same interval, Home recorded p95 frame durations between 224 ms and 733 ms and main-thread stalls up to 1.4 seconds.

Both Android TV and Android mobile call `warmAuthenticatedStartup` from their activity startup paths. Its `warmHome` helper currently fetches every section's items with unbounded `async`/`awaitAll`, even when `/home/sections` already returned those items inline. `HomeViewModel` already has the correct modern-server behavior and a bounded compatibility fallback, but the startup warmup does not.

## Goal

Remove the redundant per-section startup request storm for both Android TV and Android mobile while preserving artwork warming, stale-while-revalidate cache safety, section order, and compatibility with older servers that omit inline items.

## Approaches Considered

### 1. Reuse inline sections and bound only the compatibility fallback — recommended

Treat sections with inline items, or a declared `totalCount` of zero, as resolved. Fetch `/items` only for sections that declare content but arrive empty, with at most four requests active at once. This matches `HomeViewModel`, retains startup artwork warming, and directly removes the 31-request modern-server fan-out with minimal behavioral change.

### 2. Remove network-backed Home warmup entirely

Warm artwork only from the Room cache and leave all Home networking to `HomeViewModel`. This guarantees one network owner, but a first launch with no cache loses artwork prefetch and may visibly regress card painting.

### 3. Add repository-wide single-flight or a freshness TTL

Coalesce concurrent `/home/sections` calls or briefly reuse a recent response. This can remove the remaining overlapping top-level request, but introduces cancellation, profile identity, and freshness semantics beyond the request storm demonstrated by the bundle. It is not required for this targeted fix.

## Design

Extract the startup hydration decision into a small internal suspend helper in `StartupWarmup.kt`. The helper accepts the sections returned by `/home/sections` and a fetch callback for unresolved section IDs. It returns the resolved non-empty sections plus a `fullyResolved` flag.

For each section:

- Inline non-empty `items` means the section is complete and triggers no fallback request.
- `items.isEmpty()` with `totalCount == 0` means the section is complete but omitted from the rendered/cache result.
- `items.isEmpty()` with `totalCount > 0` triggers the compatibility fallback.
- Compatibility fallback runs through `mapConcurrentBounded(maxConcurrency = 4)`.
- Both supported fallback response shapes are honored: items nested in `response.section` and sibling top-level `response.items`.
- Results are restored to the original `/home/sections` order.
- A failed or unusable fallback leaves `fullyResolved` false.

`warmHome` writes Room only when every section is fully resolved, preserving the existing rule that a partial fetch cannot overwrite a good cached Home. Artwork warming receives only the resolved, non-empty sections.

The existing `HomeViewModel` behavior remains unchanged in this remediation. The duplicate top-level `/home/sections` requests are recorded as a separate optimization candidate; this change targets the high-cost per-section fan-out without adding cross-caller cache or cancellation semantics.

## Mobile and TV Coverage

No platform-specific implementation is needed. Both `androidTvApp/MainTvActivity.kt` and `androidApp/MainActivity.kt` call the same `android-shared` warmup function and therefore receive the fix.

The audit will also verify that neither platform has a second platform-local implementation of section hydration or another unbounded Home-section fan-out. Platform source tests will assert both activities continue routing authenticated startup through the shared warmup.

## Error Handling

The warmup remains best-effort and must not block routing or splash dismissal. API errors and network errors from a compatibility fallback mark the result partial, skip the cache write, and allow the screen-owned Home load to continue normally. Exceptions remain contained by the existing `runCatching` boundary in `warmAuthenticatedStartup`.

## Tests

Tests will be written before production changes and will cover:

1. Fully inline modern-server sections cause zero fallback calls.
2. Empty zero-count sections cause zero fallback calls and are excluded from resolved output.
3. Only genuinely unresolved sections are fetched.
4. Fallback concurrency never exceeds four.
5. Nested-section and top-level-items response shapes both hydrate correctly.
6. A failed fallback marks the result partial and prevents cache replacement behavior.
7. Original section order is preserved.
8. Both mobile and TV activities continue using `warmAuthenticatedStartup`.

Focused tests will run in `android-shared` and both application modules. Final verification will run shared tests, Android-shared tests, mobile tests and assembly, and TV tests and assembly. No device installation or launch is part of implementation verification unless separately requested.

## Success Criteria

- A modern `/home/sections` response with inline items produces no `/home/sections/{id}/items` warmup requests.
- An older partial response produces at most four concurrent fallback requests.
- The Home cache is never replaced by a partial hydration result.
- Android TV and Android mobile compile and pass their test suites.
- A follow-up diagnostics bundle should no longer show the 31-request startup section fan-out.
