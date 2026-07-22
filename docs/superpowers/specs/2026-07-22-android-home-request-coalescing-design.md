# Android Home Request Coalescing Design

## Context

The Google TV Streamer diagnostics bundle captured 15 successful
`/api/v1/home/sections` requests in 56 seconds. Three of those requests were
simultaneous when the app returned to the foreground. The calls came from
independent, legitimate consumers: authenticated startup warmup, the Home
screen ViewModel, navigation lifecycle refresh, realtime refresh, and Android
TV Watch Next synchronization. Each consumer currently calls
`SectionRepository.getHomeSections()` directly, so none can see or reuse work
already started by another consumer.

The UI remained responsive because Home is stale-while-revalidate and the
network work is asynchronous. The repeated requests are nevertheless
unnecessary server, radio, parsing, cache, and recomposition work.

## Goal

Guarantee that Home consumers share overlapping work and briefly reuse a
successful result without weakening explicit freshness, playback-return
refreshes, or server/profile isolation.

## Approaches Considered

### 1. Repository-scoped request gate — selected

Add auth-scoped single-flight and a ten-second successful-response freshness
window to `SectionRepository`. Every current and future caller receives the
same behavior without coordinating directly with other consumers.

### 2. Remove duplicate call sites

Choose one startup owner and delete selected warmup, lifecycle, or Watch Next
calls. This is initially smaller but couples correctness to a list of callers
that will change over time. It also forces tradeoffs between warm artwork,
launcher freshness, and Home freshness.

### 3. Introduce a Home data coordinator

Move Home networking, Room caching, realtime, warmup, and Watch Next behind a
new coordinator. This provides a strong long-term boundary but is broader than
the demonstrated problem and would make a focused optimization unnecessarily
risky.

## Selected Design

### Request policy

`SectionRepository.getHomeSections()` gains a request policy with two modes:

- Normal requests join an in-flight request for the same auth scope or reuse a
  successful response completed within the last ten seconds.
- Forced requests bypass a previously completed response but still join a
  matching request that was already in flight when the forced request began.

Only successful API responses enter the freshness cache. Errors and network
failures are returned normally and never suppress a later retry.

The gate serializes the small decision boundary, not unrelated repository
operations. Waiting callers are cancellable. Cancellation of one waiter must
not cancel shared work needed by the remaining callers.

### Scope isolation

Each request is keyed by the active `(serverId, profileId)` captured from
`TokenManager.snapshotCurrentScope()`. A result may be reused only for the same
key. If the active scope changes while a request is running, that result is not
stored for the new scope and a new-scope caller performs its own request.

When no complete server/profile scope is available, the repository performs a
normal network request without freshness reuse. This fail-closed behavior
prevents data from an unknown scope being shown to another user.

The auth key contains identifiers only. Access tokens, refresh tokens, and
profile tokens are never retained by the request gate.

### Caller behavior

Existing background consumers use the normal policy:

- authenticated startup warmup;
- initial `HomeViewModel` load;
- realtime refresh signals;
- Android TV Watch Next synchronization.

Explicit user refreshes and Home refreshes after returning from playback use
the forced policy. The Home navigation lifecycle ignores its first
`ON_RESUME`, because initial ViewModel construction already starts the load.
Subsequent eligible resumes continue to refresh, preserving current playback
return behavior and the existing detail-return suppression used for focus
restoration.

The stale-while-revalidate Room cache remains unchanged. It is the durable,
offline cache; the new ten-second entry is an in-memory request optimization
only.

### Timing and concurrency

The ten-second window starts when a successful response completes. A normal
request arriving within that window returns the stored response immediately.
After expiry, the first caller starts a network request and later callers join
it.

A forced request records when it began. If a matching request completes after
that point, the forced caller joins that fresh result instead of issuing a
second request. If the latest matching result completed before the forced
caller began, the forced caller starts a new request.

Time is supplied through an injectable monotonic clock so tests can advance
the freshness window deterministically without sleeping.

## Error and Lifecycle Behavior

- Failed requests do not replace the last successful entry or extend its
  freshness. Once the old entry expires, normal callers observe and can retry
  the failure.
- A server or profile switch cannot reuse or join work from the previous
  scope.
- Initial Home rendering continues to use Room immediately and does not wait
  for the network.
- Realtime bursts may still emit refresh signals, but fresh signals reuse the
  repository result instead of hitting the server.
- Watch Next remains eventually consistent and may reuse a Home response no
  more than ten seconds old.

## Tests

Tests will be written before production changes and will prove:

1. Concurrent normal requests for one scope perform one HTTP call.
2. Concurrent forced and normal requests join the same in-flight call.
3. Sequential normal requests within ten seconds reuse one successful result.
4. A normal request after expiry performs a new HTTP call.
5. A forced request after a completed result performs a new HTTP call.
6. Errors and network failures are not cached.
7. Different server or profile keys never share a result.
8. A scope change during a request cannot populate the new scope's cache.
9. Initial Home resume does not issue a second ViewModel refresh.
10. A later eligible resume requests a forced refresh.

Focused common and TV tests will run first. Final verification will run the
shared, Android-shared, mobile, and TV test suites plus both application
assemblies because `SectionRepository` is shared by Android TV and mobile.

## Success Criteria

- At most one `/home/sections` request is active for a server/profile scope.
- Normal consumers reuse a successful response for ten seconds.
- Explicit refresh and playback return fetch fresh data unless they can join a
  request already in flight.
- No response crosses a server or profile boundary.
- Existing Home, startup warmup, realtime, Watch Next, focus restoration, and
  offline behavior remain intact.
- A follow-up diagnostics capture no longer shows simultaneous Home requests
  or rapid repeated Home requests within the freshness window.
