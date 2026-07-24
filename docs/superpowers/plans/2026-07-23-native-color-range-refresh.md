# Native Color-Range Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh Android PR #86 on current `main` with safe source color-range fallback, current subtitle-source composition, and complete interaction coverage.

**Architecture:** Decode additive color-range metadata into the existing playback models, normalize its use through one Android-shared delivery-policy helper, and carry it in `VideoPlayerMediaSpec` to a video-only extractor wrapper. Corrected progressive sources are created from a subtitle-free content item; sidecars remain merged exactly once by the outer source factory.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, Android Media3/ExoPlayer, kotlin-test/JUnit 4, Gradle 8.12.

## Global Constraints

- `color_range` is additive and accepts only canonical FFmpeg `tv`, `pc`, `unknown`, or absence.
- Explicit extractor/container metadata always wins over the server fallback.
- Source range is never used as an output fallback for `SERVER_TRANSCODE_HLS`.
- HLG repair preserves an explicit range while filling only missing HLG fields.
- Dolby Vision signaling remains authoritative for Dolby Vision output.
- Subtitle configurations are removed from the content item and merged once as sidecar sources.
- Phone and TV must share the same delivery and range policy.
- Do not change SiloCast or observability code.
- Do not trigger GitHub Actions; the final HEAD pushed to #86 must contain `[skip ci]`.

---

### Task 1: Decode and Preserve the Wire Contract

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/catalog/CatalogModels.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackModels.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/model/catalog/CatalogTrackSerializationTest.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3Test.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackV3Session.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackV3SessionTest.kt`

**Interfaces:**
- Produces: `VideoTrack.colorRange: String?`
- Produces: `PlaybackSourceMetadata.colorRange: String?`
- Produces: `PlaybackPlanV3.source: PlaybackSourceV3`
- Produces: `PlaybackSourceV3.colorRange: String?`

- [ ] **Step 1: Write failing serialization and conversion tests**

Add assertions that decode catalog `"color_range":"tv"`, round-trip
`PlaybackSourceV3(colorRange = "pc")`, and convert v3 source range into
`PlaybackSourceMetadata`.

```kotlin
assertEquals("tv", track.colorRange)
assertEquals("pc", decoded.source.colorRange)
assertEquals("pc", response.playbackPlan?.source?.colorRange)
```

- [ ] **Step 2: Verify the tests fail for missing model properties**

Run:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests '*CatalogTrackSerializationTest' \
  --tests '*PlaybackProtocolV3Test' \
  :android-shared:testDebugUnitTest \
  --tests '*PlaybackV3SessionTest'
```

Expected: compilation fails because `colorRange`, `source`, and
`PlaybackSourceV3` do not exist.

- [ ] **Step 3: Add the minimal additive model fields**

```kotlin
@SerialName("color_range") val colorRange: String? = null
```

Add this property to `VideoTrack` and `PlaybackSourceMetadata`. Add
`val source: PlaybackSourceV3 = PlaybackSourceV3()` to `PlaybackPlanV3` and:

```kotlin
@Serializable
data class PlaybackSourceV3(
    @SerialName("color_range") val colorRange: String? = null,
)
```

Map `source.colorRange` into the legacy session response.

- [ ] **Step 4: Run focused tests**

Run the command from Step 2.

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add shared/src android-shared/src
git commit -m "fix(playback): decode source color range"
```

### Task 2: Repair Missing Media3 Range Without Overwriting Metadata

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/DolbyVisionColorInfoExtractorsFactory.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/DolbyVisionProfile7Transformer.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/DolbyVisionColorInfoExtractorsFactoryTest.kt`

**Interfaces:**
- Produces: `Format.withValidatedColorRange(expectedColorRange: String?): Format`
- Extends: `SiloMediaTransformTag.expectedColorRange: String?`

- [ ] **Step 1: Write failing range and composition tests**

Cover limited, full, unknown, explicit precedence, HLG with explicit full
range, and Dolby Vision with a conflicting source fallback.

```kotlin
assertEquals(C.COLOR_RANGE_LIMITED, source.withValidatedColorRange("tv").colorInfo?.colorRange)
assertEquals(C.COLOR_RANGE_FULL, source.withValidatedColorRange("pc").colorInfo?.colorRange)
assertSame(explicit, explicit.withValidatedColorRange("tv"))
assertEquals(C.COLOR_TRANSFER_HLG, hlgFull.withValidatedDynamicRangeColorInfo("hlg").colorInfo?.colorTransfer)
assertEquals(C.COLOR_RANGE_FULL, hlgFull.withValidatedDynamicRangeColorInfo("hlg").colorInfo?.colorRange)
assertEquals(C.COLOR_RANGE_LIMITED, dolby.withDolbyVisionHdrColorInfo().colorInfo?.colorRange)
```

- [ ] **Step 2: Verify tests fail**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*DolbyVisionColorInfoExtractorsFactoryTest'
```

Expected: range helper is unresolved and the HLG/full composition assertion
fails under the old all-or-nothing conflict rule.

- [ ] **Step 3: Implement minimal video-only repair**

Add `expectedColorRange` through the extractor wrapper. For video tracks,
compose `ColorInfoTrackOutput` inside the optional Dolby transformer. Implement:

```kotlin
internal fun Format.withValidatedColorRange(expectedColorRange: String?): Format {
    if (!MimeTypes.isVideo(sampleMimeType)) return this
    val expected = when (expectedColorRange?.trim()?.lowercase()) {
        "tv" -> C.COLOR_RANGE_LIMITED
        "pc" -> C.COLOR_RANGE_FULL
        else -> return this
    }
    val current = colorInfo
    if (current != null && current.colorRange != -1) return this
    val repaired = (current?.buildUpon() ?: ColorInfo.Builder())
        .setColorRange(expected)
        .build()
    return buildUpon().setColorInfo(repaired).build()
}
```

Change HLG validation so explicit color space/transfer conflicts still fail
closed, but a known range is preserved rather than blocking missing HLG
space/transfer repair.

- [ ] **Step 4: Run focused tests**

Run the command from Step 2.

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add android-shared/src/androidMain android-shared/src/androidUnitTest
git commit -m "fix(playback): apply validated color range fallback"
```

### Task 3: Enforce Safe Delivery Policy in One Shared Helper

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackColorRangeFallback.kt`
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackColorRangeFallbackTest.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/VideoPlayerMediaSpec.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/VideoPlayerMediaMounter.kt`

**Interfaces:**
- Produces: `PlaybackExecutionPlan?.validatedColorRangeFallback(): String?`
- Extends: `VideoPlayerMediaSpec.expectedColorRange: String?`

- [ ] **Step 1: Write failing delivery-policy tests**

```kotlin
assertEquals("tv", originalPlan.validatedColorRangeFallback())
assertEquals("pc", progressiveRemuxPlan.validatedColorRangeFallback())
assertNull(transcodePlan.validatedColorRangeFallback())
assertNull(unknownPlan.validatedColorRangeFallback())
```

- [ ] **Step 2: Verify tests fail**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*PlaybackColorRangeFallbackTest'
```

Expected: compilation fails because `validatedColorRangeFallback` is absent.

- [ ] **Step 3: Implement canonical delivery gating**

```kotlin
fun PlaybackExecutionPlan?.validatedColorRangeFallback(): String? {
    val plan = this ?: return null
    if (plan.delivery == PlaybackDelivery.SERVER_TRANSCODE_HLS) return null
    return plan.source.colorRange
        ?.trim()
        ?.lowercase()
        ?.takeIf { it == "tv" || it == "pc" }
}
```

Add `expectedColorRange` to `VideoPlayerMediaSpec` and forward it from both
mount and refresh functions into `SiloPlayerFactory.buildMediaItem`.

- [ ] **Step 4: Run focused tests**

Run the command from Step 2.

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add android-shared/src
git commit -m "fix(playback): gate source range by delivery"
```

### Task 4: Preserve Single-Mounted Sidecars in Corrected Sources

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlayerFactory.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SiloPlayerFactorySubtitleParserTest.kt`

**Interfaces:**
- Consumes: `SiloMediaTransformTag.expectedColorRange`
- Consumes: `VideoPlayerMediaSpec.expectedColorRange`

- [ ] **Step 1: Write a failing source-composition regression test**

Add a source-level assertion alongside the existing sidecar architecture tests:

```kotlin
assertTrue(
    source.contains("mediaSourceFactory(tag).createMediaSource(contentItem)"),
    "Corrected content must use the subtitle-free item so sidecars are merged once.",
)
assertFalse(source.contains("mediaSourceFactory(tag).createMediaSource(mediaItem)"))
```

- [ ] **Step 2: Verify the test fails**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*SiloPlayerFactorySubtitleParserTest'
```

Expected: the corrected-factory call is absent.

- [ ] **Step 3: Implement corrected factory selection**

Replace fixed HLG/Dolby factories with a corrected factory function accepting
mode, dynamic range, and color range. Retain the current DRM provider and load
policy, and create non-HLS content with:

```kotlin
val tag = localConfiguration.tag as? SiloMediaTransformTag
mediaSourceFactory(tag).createMediaSource(contentItem)
```

Keep the existing outer `subtitleConfigurations.mapTo(...)` merge unchanged.

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*SiloPlayerFactorySubtitleParserTest' \
  --tests '*DolbyVisionColorInfoExtractorsFactoryTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add android-shared/src
git commit -m "fix(playback): compose range correction with sidecars"
```

### Task 5: Wire Phone and TV, Verify, and Update PR #86

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt`

**Interfaces:**
- Consumes: `PlaybackExecutionPlan?.validatedColorRangeFallback()`
- Consumes: `VideoPlayerMediaSpec.expectedColorRange`

- [ ] **Step 1: Add phone and TV policy wiring**

At every initial mount and subtitle refresh media-spec construction, add:

```kotlin
expectedColorRange = plan.validatedColorRangeFallback(),
```

- [ ] **Step 2: Run all unit suites and assemble both apps**

Run:

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleDebug \
  :androidTvApp:assembleDebug \
  --rerun-tasks --no-parallel
```

Expected: build succeeds with zero test failures.

- [ ] **Step 3: Verify scope**

Run:

```bash
git diff --check upstream/main...HEAD
git diff --name-only upstream/main...HEAD | rg -i 'silocast|silo[-_ ]?cast|/cast/'
```

Expected: the first command is silent and the second returns no matches.

- [ ] **Step 4: Commit phone and TV wiring**

```bash
git add androidApp/src androidTvApp/src
git commit -m "fix(playback): wire native color range on Android [skip ci]"
```

- [ ] **Step 5: Install the arm64 TV APK without launching**

Run:

```bash
adb -s 192.168.1.128:5555 install -r \
  androidTvApp/build/outputs/apk/debug/androidTvApp-arm64-v8a-debug.apk
```

Expected: `Success`.

- [ ] **Step 6: Update #86**

Confirm the remote head still equals the reviewed old SHA, then push with
lease:

```bash
git ls-remote upstream refs/heads/codex/native-color-range
git push --force-with-lease=refs/heads/codex/native-color-range:11619a40a9daa31acdf007de0daef5533d546d69 \
  git@github.com:Silo-Server/silo-android.git \
  HEAD:refs/heads/codex/native-color-range
gh pr ready 86 -R Silo-Server/silo-android
```

Expected: push succeeds without overwriting an unexpected contributor update,
and #86 becomes ready for review.
