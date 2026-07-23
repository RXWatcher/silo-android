# Native Color-Range Refresh Design

## Goal

Refresh pull request #86 on current `main` so Android can use the server's
FFmpeg `color_range` metadata as a safe Media3 fallback without regressing
subtitle mounting or applying source metadata to a materially different
transcoded output.

## Scope

- Preserve the additive `color_range` fields for catalog, legacy playback, and
  playback protocol v3 models.
- Carry the expected range through the phone and TV media specifications.
- Map canonical FFmpeg `tv` to Media3 limited range and `pc` to full range only
  when extractor metadata leaves the range unspecified.
- Preserve explicit extractor/container range over the server fallback.
- Compose the range fallback with existing HLG and Dolby Vision repairs.
- Resolve the current `SiloPlayerFactory` conflict around the newer
  content/sidecar split without mounting subtitle configurations twice.
- Update Quick104's existing #86 branch after verification.

## Delivery Safety

The protocol-v3 field describes the source file, not necessarily a transcoded
output. The client may therefore use it for byte-preserving delivery:

- `ORIGINAL_HTTP`
- `SERVER_REMUX_PROGRESSIVE`
- `SERVER_REMUX_HLS` when the extractor path can consume the fallback

The client must not use source range as a fallback for
`SERVER_TRANSCODE_HLS`. A future server contract can expose effective output
range when that is needed.

Legacy plans may continue to consume `PlaybackSourceMetadata.colorRange`
because that field is already part of the effective playback response rather
than the new protocol-v3 source descriptor.

## Media-Source Composition

Non-HLS playback selects a corrected Media3 source factory from the media tag.
The selected factory receives the current DRM provider and load-error policy.
It must create the content source from the subtitle-free `contentItem`; the
outer factory remains solely responsible for merging sidecar subtitle sources.

Video track output applies repairs in this order:

1. Fill a missing color range from canonical server metadata.
2. Apply validated HLG metadata without overwriting explicit range.
3. Apply Dolby Vision metadata rules, which remain authoritative for Dolby
   Vision signaling.

Audio and non-video tracks pass through unchanged.

## Tests

Test-first coverage will prove:

- `tv`, `pc`, unknown, absent, and explicit-container precedence.
- HLG and Dolby Vision composition with missing and explicit ranges.
- Protocol-v3 source range is forwarded for original/remux delivery and
  suppressed for server transcode.
- Corrected content sources receive the subtitle-free media item, preventing
  duplicate sidecar mounting.
- Existing protocol serialization and session-conversion behavior remains
  compatible.

After focused tests pass, run all four Android unit-test suites, compile phone
and TV, assemble the TV APK, and install the arm64 build on the Shield without
launching it automatically.

## Integration

Rebase the refreshed implementation on current upstream `main`. Once all local
verification and the Shield install succeed, update the existing
`codex/native-color-range` branch directly using `--force-with-lease`, then
mark #86 ready for review. Do not trigger or dispatch unrelated workflows.
