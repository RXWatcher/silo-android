# Transactional Android Subtitle Coordinator Design

## Status

Approved for implementation planning on 2026-07-23.

## Goal

Make subtitle behavior correct end to end on Android TV and Android mobile:
pre-playback and in-playback selection, Off, external sidecars, burn-in,
embedded text and bitmap tracks, downloaded subtitles, local Media3 tracks,
rapid selection changes, refreshes, session replacement, style behavior, and
preference persistence.

The client must never expose a replacement subtitle or stop the working
playback session until the replacement is validated and still represents the
user's latest intent.

## Scope

This design covers Android client playback only:

- shared Android playback/session coordination;
- shared subtitle identity and transition models;
- Android mobile playback integration;
- Android TV playback integration;
- subtitle refresh, persistence, and Media3 remount behavior;
- automated and device release gates.

It does not change:

- SiloCast;
- server behavior or APIs;
- observability, GlitchTip, or Sentry;
- CI workflows or remote build triggers.

## Rejected Approaches

### Independent ViewModel patching

Adding more request IDs, generation counters, and rollback branches directly to
both player ViewModels duplicates state-machine logic and permits the two
clients to diverge. The prior implementation attempt demonstrated that this
approach is too difficult to reason about as a complete system.

### Restart playback for every subtitle change

Restarting is simpler than staging a replan, but it visibly disrupts playback,
loses continuity, and is unacceptable for normal TV subtitle controls.

## Architecture

### Shared transactional coordinator

A shared, pure `SubtitleTransitionCoordinator` owns subtitle intent and
transition decisions. TV and mobile are adapters that supply player/session
events and execute coordinator effects.

The coordinator has three externally visible states:

- `Committed`: the subtitle identity currently backed by the active playback
  session and mounted Media3 item.
- `Applying`: a newer user intent is being prepared while `Committed` remains
  active and visible.
- `Failed`: the most recent intent failed; the committed selection remains
  active, and the UI may show a nonfatal message.

Only the coordinator may decide that a pending intent is committed. ViewModels
must not optimistically replace committed subtitle tracks, selected state, or
persistence.

### Typed subtitle identity

All selection paths use typed identity rather than display labels:

- `Off`;
- `ServerSidecar(serverIndex)`;
- `ServerBurnIn(serverIndex)`;
- `Embedded(serverIndex, mediaIdentity)`;
- `Downloaded(downloadId, mediaIdentity)`;
- `LocalMedia3(mediaIdentity)`.

`mediaIdentity` prefers a stable Media3 format ID. Its metadata fallback
contains normalized label, language, codec/MIME family, forced status, and
hearing-impaired status where available. Exact IDs have global priority over
metadata fallback. A non-server Media3 ID cannot be mistaken for a server
sidecar. Embedded bitmap rows may use their narrow codec/language/label/forced
fallback when no server-authored Media3 ID exists.

Display labels are presentation data only and never establish identity.

### Staged playback replan

`PlaybackSessionManager` gains a staged replan operation:

1. Request and validate the server response without changing
   `activeVideoAttempt` or stopping the old session.
2. Return a `StagedVideoReplan` containing the candidate session, playback
   plan, stream, subtitle artifacts, and a one-use commit handle.
3. The coordinator validates the candidate against the pending typed intent.
4. If the intent is still current and the candidate is valid, commit the
   staged replan atomically: update the active attempt, stop the prior session,
   and publish the replacement snapshot.
5. If superseded or invalid, discard the staged replan, stop only the candidate
   session when necessary, and leave the prior attempt active.

A staged result may be committed once. Content/version resets invalidate all
outstanding staged handles.

### Candidate validation rules

- `ServerSidecar`: the returned render/convert plan must contain a sidecar with
  the exact stable server index.
- `ServerBurnIn`: the returned plan must select the requested server track in
  burn-in mode; no sidecar is required.
- `Embedded`: the selected embedded track must remain present in the plan or
  mounted Media3 snapshot, depending on playback mode.
- `Downloaded`: the downloaded identity remains client-owned. Its URL is
  rebased to the candidate session before commit.
- `LocalMedia3`: the main media must remain compatible with the saved local
  identity; remount restoration uses exact ID first and metadata second.
- `Off`: the candidate must not select or restore a subtitle.

## Data Flow

### User selection

1. The adapter sends `Select(intent)` with a monotonically increasing intent
   generation.
2. The coordinator enters `Applying(intent)` but continues publishing the
   committed selection to Media3.
3. Local-only choices that require no session replacement are validated
   against the mounted track snapshot and committed synchronously.
4. Server-backed choices create a staged replan.
5. A newer selection supersedes the older generation. An older staged response
   can only be discarded, never committed.
6. A valid latest response commits the session, artifacts, selected state, and
   persistence together.

The UI may show `Applying…` beside the pending choice. The old working subtitle
remains visible until commit.

### Rapid changes

A→B and A→Off use latest-intent-wins semantics:

- at most one staged server request is active;
- only the newest queued intent is retained;
- the active result is discarded if a newer intent exists;
- the newest intent starts from the still-working committed session;
- a failed newest intent leaves the original committed session and subtitle
  unchanged.

Audio, quality, and route changes merge with the latest subtitle intent rather
than replacing it. Each independent user preference has its own typed field in
the queued replan request.

### Subtitle refresh and downloads

Every refresh has an owner containing:

- content generation;
- content ID;
- media file/version ID;
- active session ID;
- refresh request generation;
- subtitle intent generation.

Only the newest owned response may merge downloaded rows or request
auto-selection. A stale response is ignored. Downloaded URLs are generated for
the active or staged target session, never copied from a stopped session.

Refresh auto-selection is a pending typed downloaded identity, not a raw label.
It terminates on commit, explicit user selection, failure, reset, or bounded
mount mismatch.

### Media3 remount

The committed selection snapshot includes a typed restore identity. After a
MediaItem replacement, the adapter resolves that identity against the new
track snapshot:

1. exact stable ID;
2. exact server artifact index;
3. narrow metadata fallback appropriate to the identity type.

Repeated or transitional callbacks do not consume refresh/mount retry budgets.
Only distinct, meaningful text-track snapshots count.

## Error Handling

- Missing required sidecar: discard the candidate session and retain the
  current session.
- Invalid burn-in selection: discard the candidate and retain the current
  session.
- API/network failure: retain the current session and committed subtitle;
  clear `Applying`; show a nonfatal message.
- Superseded response: silently discard the candidate and process the newest
  intent.
- Content/version reset: invalidate staged replans, refresh owners, pending
  intents, and remount restoration state.
- Media3 restore miss: retain the committed logical preference, report a
  bounded nonfatal failure for the current mount, and do not select a
  coincidental label match.

## Persistence

Persistence occurs only when the same subtitle transition commits.

- Failed or superseded choices are never written.
- Off is persisted as an explicit typed value.
- Server identities persist stable server indices and catalog fingerprints.
- Downloaded identities persist their download identity without a session URL.
- Local identities persist both stable ID and normalized metadata fallback.
- Existing valid legacy fingerprints remain readable.

Restoration uses exact IDs globally before metadata fallback. Metadata fallback
cannot resolve a local identity to a server sidecar.

## UI Behavior

- The currently committed subtitle remains checked and active while a new
  server-backed choice is applying.
- The pending row displays `Applying…`.
- On success, the pending row becomes committed without restarting visible
  playback.
- On failure, the pending marker clears, the prior row remains selected, and a
  concise nonfatal message appears.
- Selecting Off follows the same transactional rule when a server replan is
  required.
- Existing subtitle styling remains unchanged and applies to the committed
  track.

## Testing

### Shared coordinator

Table-driven state-machine tests cover:

- Off→A, A→B, A→Off;
- A→B→C before responses;
- success, failure, supersession, and reset;
- sidecar, burn-in, embedded, downloaded, and local identities;
- audio/quality/route merge ordering;
- persistence only on commit.

### Session manager

Tests prove:

- staging does not replace `activeVideoAttempt`;
- staging does not stop the old session;
- invalid/superseded candidates stop only the candidate session;
- commit swaps the active attempt once and then stops the old session;
- sidecar and burn-in validation differ correctly.

### TV and mobile adapters

Both clients test:

- pre-playback and in-playback selection;
- identical latest-intent behavior;
- same-label and duplicate language/codec rows;
- embedded PGS/VobSub forced/full pairs;
- downloaded subtitle refresh and session rebasing;
- stale refresh response rejection;
- local Media3 remount restoration;
- Off and failure rollback;
- content/version/session reset;
- persistence after success and never after failure.

### Release gate

Before the branch is called ready:

1. focused tests pass;
2. the complete local Android test suite passes;
3. phone and TV debug assemblies pass;
4. `git diff --check` passes;
5. prohibited-scope audit is clean;
6. a fresh whole-range review reports zero Critical and zero Important
   findings;
7. real-device validation is performed only after explicit user approval.

## Success Criteria

- Selecting a subtitle before playback and during playback produces the same
  committed result.
- Selecting a subtitle never silently resolves a different same-label track.
- A failed or superseded selection never stops or corrupts working playback.
- The old subtitle remains visible until a replacement commits.
- Off cannot be undone by an older response or stale refresh.
- Burn-in, external sidecar, embedded bitmap, downloaded, and local tracks all
  follow explicit validation rules.
- TV and mobile share transition semantics.
- No excluded subsystem is modified.
