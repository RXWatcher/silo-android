# Android Subtitle Artifact Identity Design

## Problem

Protocol V3 materializes one subtitle artifact for the selected server subtitle
index. On Android TV, a subtitle-change replan preserves previously materialized
artifacts and mounts the newly selected artifact beside them. Every converted
artifact currently receives the runtime label `Server subtitle`.

Selection then resolves a requested server row by matching that non-unique
runtime label against Media3 text tracks. When two English artifacts are
mounted, the first old artifact wins. In the reproduced case, selecting English
SDH (combined server index 4) selected the previously mounted English Forced
artifact (index 3), whose only cues occur around 6:28.

Android mobile has a related replan-adoption gap: it adopts the replacement
stream and timeline but leaves `subtitleTracks` on the pre-replan list, so a
newly materialized artifact is not adopted reliably.

## Requirements

- Selecting a subtitle during playback must select the exact combined server
  subtitle index requested by the user.
- Two tracks with the same language, codec, and display label must remain
  distinguishable.
- A subtitle-change replan must replace the previously materialized planned
  artifact rather than accumulating stale planned artifacts.
- Catalog-only rows must remain visible in pickers without being mounted as
  empty sidecars.
- TV and mobile must adopt the same newly planned subtitle identity.
- Existing subtitle appearance, synchronization, and timeline-offset behavior
  must remain unchanged.
- Do not modify SiloCast, observability, GlitchTip, Sentry, server code, or CI
  configuration.
- Do not trigger remote builds.

## Considered Approaches

### 1. Match by label, language, codec, and flags

This is a small change, but it still fails when two same-language artifacts
share equivalent metadata. It also keeps identity dependent on user-facing
metadata, which can be localized or normalized.

### 2. Keep every prior artifact and encode the server index into Media3 identity

This makes matching deterministic, but continually mounting old sidecars wastes
requests and leaves stale sources participating in every media preparation.

### 3. Stable server-index identity plus replacement semantics

This is the selected approach. Carry the combined server subtitle index into a
unique internal Media3 identity, resolve requests against that identity, and
replace the previous planned artifact when a replan materializes another one.
Catalog rows remain separate display metadata.

## Design

### Shared artifact identity

Add a deterministic internal identity derived from
`PlayerSubtitleInfo.index`. The identity must survive conversion from the V3
plan to `PlayerSubtitleInfo`, subtitle configuration construction, and Media3
track extraction. It must not be shown as picker copy.

Selection helpers will prefer the stable identity. Metadata matching remains a
compatibility fallback for legacy sessions that do not expose it, but a generic
runtime label such as `Server subtitle` must never short-circuit identity
resolution.

### TV replan adoption

When a V3 decision supplies a planned subtitle artifact, rebuild the effective
subtitle choices from:

1. the current catalog rows, and
2. the new decision's planned artifact.

Do not preserve a previous `server_artifact` row with a different combined
index. Preserve genuinely downloaded/user-added subtitle rows only where the
existing refresh flow requires them.

After Media3 reports the remounted tracks, resolve
`pendingSubtitleSelectServerIndex` through the stable identity and apply the
override to that exact track.

### Mobile replan adoption

When mobile adopts a successful V3 replan, rebuild `subtitleTracks` using the
effective catalog and the decision's newly planned subtitle artifact. Update
`selectedSubtitleIndex` to the corresponding mounted-list ordinal while
adopting the replacement stream.

The existing mobile backend may continue mounting only the selected sidecar;
the important requirement is that the post-replan state contains the new
artifact rather than the stale or blank catalog row.

### Compatibility

Legacy playback responses without stable artifact identity continue using the
existing label/language/codec fallback. Direct embedded bitmap tracks retain
their decoder-backed selection path and blank sidecar URL.

## Error Handling

- If the requested server index is absent from the new plan, leave the current
  subtitle selection unchanged and keep the request pending only for the
  existing bounded retry window.
- Never fall back from a known stable identity to a different track merely
  because its label and language match.
- A failed user-initiated replan continues playback on the previously mounted
  route, matching existing behavior.

## Verification

Add focused regression tests proving:

- two `Server subtitle` artifacts resolve by different server indexes;
- selecting English SDH cannot resolve to the older English Forced artifact;
- TV replan composition drops the stale planned artifact while retaining
  catalog-only rows;
- mobile replan adoption installs the newly planned artifact and selected
  ordinal;
- legacy metadata-only tracks still resolve;
- Off selection and embedded bitmap behavior remain unchanged.

Run the focused shared, TV, and mobile unit tests, then the full Android unit
test suite and both phone/TV debug assemblies. No device launch or remote build
is part of this change.
