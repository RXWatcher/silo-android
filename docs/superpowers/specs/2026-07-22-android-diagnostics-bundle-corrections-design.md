# Android Diagnostics Bundle Corrections

## Context

Inspection of a real manual report from the Google Streamer showed that upload and archive integrity work, but several fields lose diagnostic value before the bundle reaches the server:

- playback failures are recorded only as a generic event;
- recent playback session IDs are absent;
- manual log categories collapse to `other`;
- `bytes_gz` is always zero;
- a permissive JWT pattern redacts dotted codec names; and
- network paths collapse valid endpoint families into `other/{id}`.

The correction must improve correlation without admitting media names, raw URLs, media/content/file identifiers, query strings, tokens, or server-provided prose into diagnostic evidence. Opaque playback-session IDs remain permitted solely for server-log correlation, as in the parent diagnostics design.

## Design

### Structured playback evidence

`VideoPlaybackStartResult.Error` will carry an optional diagnostics-only failure code in addition to its existing user-facing message. Starters will populate that code only from a bounded, normalized value: a server terminal reason such as `no_alternate_version`, or a client-owned constant for known local failure classes. Arbitrary exception text and server messages remain excluded.

`VideoPlaybackSessionCoordinator` will emit the failure code through a narrow `DiagnosticsPlaybackLogger` API. The playback attribute registry will allow only the new safe field.

### Recent playback session correlation

A process-local, thread-safe tracker will retain at most 20 recent playback session IDs, each bounded to 128 characters. `PlaybackSessionLifecycle` will record an ID whenever it adopts or starts an active session.

Manual reports and crash runtime snapshots will read the tracker. The tracker will be cleared whenever the diagnostics identity/privacy gate closes, preventing IDs from crossing server, account, profile, consent, or ownership boundaries.

### Accurate log summaries

A pure summary helper will inspect the redacted JSONL that is actually included in a report. It will calculate:

- newline count;
- gzip byte count;
- distinct recognized categories; and
- existing dropped-line/debug flags supplied by capture state.

Malformed lines will still count as lines but will not invent categories. Empty logs produce zero lines, zero compressed bytes, and no categories.

Manual capture will use this helper for pending-report metadata. Bundle construction will recompute the summary after its final redaction pass so the embedded manifest describes the exact `logs.jsonl` bytes shipped to the server.

### JWT redaction

JWT detection will require three base64url segments whose decoded header and payload are JSON objects. This preserves redaction for real JWTs while leaving dotted decoder and codec identifiers such as `c2.android.aac.decoder` intact. Explicit sensitive-value redaction remains unchanged.

### Network route templates

Network diagnostics will use an allowlisted route-template matcher. It may preserve known static endpoint segments, for example `/api/v1/playback/start` and `/api/v1/playback/route-events`, while replacing dynamic identifiers with `{id}`. Query strings, fragments, malformed paths, and unknown endpoint families continue to collapse to safe generic routes.

## Privacy and lifecycle invariants

- Never log content IDs, file IDs, media titles, raw URLs, query strings, request/response bodies, server messages, exception messages, tokens, or arbitrary attributes.
- Session IDs remain bounded correlation identifiers and are cleared at every diagnostics gate transition.
- Unknown failure reasons and paths degrade to generic values instead of being copied through.
- All manifest summaries describe already-redacted artifacts.

## Verification

Focused unit tests will cover structured failure propagation, tracker bounds and clearing, manual and bundled summaries, real-JWT versus codec-name behavior, and static/dynamic route sanitization. Existing diagnostics privacy and bundle tests plus Android shared tests will then run as regression coverage.
