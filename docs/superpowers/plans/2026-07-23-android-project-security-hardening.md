# Android Project Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every approved non-SiloCast finding in one security PR stacked on the corrected performance branch.

**Architecture:** Centralize origin and path trust decisions, then make every transport/storage caller consume those decisions. Preserve supported features through explicit HTTP consent, private HTTPS-style WebView resources, bounded streaming, scoped PiP capabilities, and reproducible verified build inputs.

**Tech Stack:** Kotlin 2.1.20, Android SDK 36/minSdk 24, Ktor 3.1.2, OkHttp/Media3 1.10.1, AndroidX WebKit 1.16.0, jsoup 1.22.2, Gradle 8.12, GitHub Actions.

## Global Constraints

- Work on `security/android-project-hardening` after rebasing it onto the verified performance branch.
- Deliver one security PR.
- Do not modify any path containing `SiloCast`, `silocast`, or `_silocast`.
- Do not add observability, GlitchTip, or Sentry.
- Keep self-hosted HTTP available after explicit origin-scoped confirmation.
- Keep explicit playback-plan headers, but never carry them across origins.
- Preserve existing safe download/state paths and valid local data.
- No device installation or app launch unless separately requested.
- Every production behavior change requires a failing test first.

---

### Task 1: Shared HTTP origin policy

**Files:**
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/network/HttpOriginPolicy.kt`
- Create: `shared/src/commonTest/kotlin/org/siloserver/silo/network/HttpOriginPolicyTest.kt`

**Interfaces:**
- Produces `data class HttpOrigin(scheme: String, host: String, port: Int)`.
- Produces `fun httpOrigin(url: String): HttpOrigin?`.
- Produces `fun isSameHttpOrigin(serverUrl: String, requestUrl: String): Boolean`.

- [ ] **Step 1: Write failing normalization tests**

```kotlin
@Test
fun defaultPortsAndCaseNormalize() {
    assertTrue(isSameHttpOrigin("HTTPS://Silo.Example", "https://silo.example:443/a"))
    assertTrue(isSameHttpOrigin("http://silo.example", "http://SILO.EXAMPLE:80/a"))
}

@Test
fun schemeHostPortAndUserInfoMismatchesFailClosed() {
    assertFalse(isSameHttpOrigin("https://silo.example", "http://silo.example/a"))
    assertFalse(isSameHttpOrigin("https://silo.example", "https://cdn.silo.example/a"))
    assertFalse(isSameHttpOrigin("https://silo.example", "https://silo.example:444/a"))
    assertNull(httpOrigin("https://user@silo.example/a"))
    assertNull(httpOrigin("file:///tmp/a"))
}
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :shared:testDebugUnitTest \
  --tests org.siloserver.silo.network.HttpOriginPolicyTest
```

Expected: missing API compilation failure.

- [ ] **Step 3: Implement with Ktor `Url`**

```kotlin
fun httpOrigin(raw: String): HttpOrigin? = runCatching {
    val url = Url(raw)
    if (url.user != null || url.password != null) return null
    val scheme = url.protocol.name.lowercase()
    if (scheme != "http" && scheme != "https") return null
    HttpOrigin(
        scheme = scheme,
        host = url.host.lowercase(),
        port = url.port,
    )
}.getOrNull()
```

Use Ktor's effective port and reject blank/invalid hosts.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :shared:testDebugUnitTest \
  --tests org.siloserver.silo.network.HttpOriginPolicyTest
git add shared
git commit -m "fix(network): centralize authenticated origin policy"
```

---

### Task 2: Apply origin policy to Ktor, OkHttp, Media3, reader, and subtitles

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/network/AuthInterceptorImpl.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/network/SiloAuthPluginPinTest.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/MediaAuthInterceptor.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/AuthenticatedDataSourceFactory.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/MediaAuthInterceptorTest.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/AuthenticatedDataSourceFactoryTest.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/reader/ReaderFileResolverTest.kt`

- [ ] **Step 1: Write failing cross-origin credential tests**

For Ktor and OkHttp, assert a foreign host receives none of
`Authorization`, `X-Profile-Id`, or `X-Profile-Token`, and a foreign 401 causes
zero refresh attempts. For Media3:

```kotlin
@Test
fun foreignOriginReceivesOnlyExplicitTargetHeaders() {
    assertEquals(
        mapOf("X-Stream-Scope" to "route-7"),
        authenticatedHeadersFor(
            serverUrl = "https://silo.example",
            requestUrl = "https://cdn.example/video",
            sessionHeaders = sessionHeaders,
            explicitHeaders = mapOf("X-Stream-Scope" to "route-7"),
        ),
    )
}
```

Add same-origin, subdomain, port mismatch, and relative URL cases.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  --tests '*SiloAuthPluginPinTest' \
  --tests '*MediaAuthInterceptorTest' \
  --tests '*AuthenticatedDataSourceFactoryTest'
```

Expected: foreign requests contain Silo credentials.

- [ ] **Step 3: Gate session authentication before request construction**

In each transport:

```kotlin
val sameOrigin = isSameHttpOrigin(snapshot.serverUrl, request.url.toString())
if (!sameOrigin) return chain.proceed(original)
```

For Media3, merge session headers only when same-origin:

```kotlin
val session = if (isSameHttpOrigin(serverUrl, dataSpec.uri.toString())) {
    snapshot.asRequestHeaders()
} else {
    emptyMap()
}
```

Resolve relative reader/subtitle URLs first. Do not trigger refresh for a
foreign 401.

- [ ] **Step 4: Add a real redirect regression using MockWebServer**

Add MockWebServer test dependency if absent. Server A responds 302 to Server B.
Assert Server B captures no Silo or explicit target-scoped authorization
header. Configure Media3/OkHttp to fail closed if the underlying client would
retain a sensitive header.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest
git add shared android-shared androidApp gradle
git commit -m "fix(network): keep credentials on the silo origin"
```

---

### Task 3: Explicit cleartext HTTP consent on phone and TV

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/network/CleartextConsentStore.kt`
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/network/CleartextConsentStoreTest.kt`
- Modify: phone/TV server setup state, ViewModels, screens, and DI modules
- Modify: existing phone/TV server setup tests

**Interfaces:**
- `CleartextConsentStore.isApproved(origin): Boolean`.
- `CleartextConsentStore.approve(origin)`.
- Setup state exposes `pendingCleartextUrl: String?`.
- ViewModels expose `confirmCleartextConnection()` and `cancelCleartextConnection()`.

- [ ] **Step 1: Write failing ViewModel tests**

For both clients:

```kotlin
@Test
fun successfulHttpFallbackStopsForConfirmationBeforePersistence() = runTest {
    connect("silo.lan") // HTTPS fails; HTTP setup succeeds
    assertEquals("http://silo.lan", state.pendingCleartextUrl)
    assertEquals(emptyList(), tokenManager.serverUrlWrites)

    viewModel.confirmCleartextConnection()
    advanceUntilIdle()
    assertEquals(listOf("http://silo.lan"), tokenManager.serverUrlWrites)
}
```

Also test cancel, origin-specific consent, and HTTPS bypass.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew \
  :androidApp:testDebugUnitTest --tests '*ServerSetup*Test' \
  :androidTvApp:testDebugUnitTest --tests '*TvServerSetup*Test'
```

- [ ] **Step 3: Implement per-origin DataStore consent**

Store only `sha256(normalizedOrigin)` keys. Do not store credentials or raw
URLs. When HTTP succeeds without approval, preserve the destination in pending
state but do not call `setServerUrl` or navigate.

- [ ] **Step 4: Add phone and TV confirmation UI**

Use existing Aurora dialogs/components. Show the normalized HTTP origin and:

```text
This connection is not encrypted. Anyone on the network may see or change
traffic, including your sign-in. Continue only on a network you trust.
```

Buttons: `Cancel` and `Use HTTP`.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :android-shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidTvApp:testDebugUnitTest
git add android-shared androidApp androidTvApp
git commit -m "fix(auth): require consent before cleartext login"
```

---

### Task 4: Parser-backed EPUB sanitization

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `androidApp/build.gradle.kts`
- Replace implementation: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/reader/reflow/EpubHtmlSanitizer.kt`
- Modify: `EpubHtmlSanitizerTest.kt`

- [ ] **Step 1: Add failing malformed/mutation-XSS tests**

Include misnested SVG/MathML, encoded URLs, external CSS, style blocks, forms,
`srcdoc`, event handlers, and script resources. Assert valid headings, ruby,
SVG, MathML, tables, anchors, and relative images remain.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*EpubHtmlSanitizerTest'
```

- [ ] **Step 3: Add pinned jsoup and allowlist**

Add `org.jsoup:jsoup:1.22.2`. Parse as an HTML body fragment, clean using a
project-owned `Safelist`, then post-validate every `href`, `src`, and
`xlink:href` through the relative-resource policy. Remove `style`, `link`,
`base`, scripts, forms, frames, objects, embeds, and all `on*` attributes.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*EpubHtmlSanitizerTest'
git add gradle androidApp
git commit -m "fix(reader): sanitize epub with parsed allowlist"
```

---

### Task 5: Private-origin WebView reader

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `androidApp/build.gradle.kts`
- Create: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/reader/reflow/EpubResourcePathHandler.kt`
- Modify: `ReflowWebView.kt`
- Modify: `reader.html`
- Modify: reader WebView tests

- [ ] **Step 1: Write failing policy/path tests**

Assert the source disables file/content access, loads
`https://appassets.androidplatform.net/assets/reader/reflow/reader.html`, and
intercepts requests. Test the path handler returns 404 for `../`, symlink, or
out-of-root paths.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ReflowWebView*Test'
```

- [ ] **Step 3: Add WebKit 1.16.0 and asset loader**

Configure:

```kotlin
settings.allowFileAccess = false
settings.allowContentAccess = false
settings.allowFileAccessFromFileURLs = false
settings.allowUniversalAccessFromFileURLs = false
```

Serve shell assets and canonicalized EPUB resources through
`WebViewAssetLoader`. Return an empty 404 `WebResourceResponse` instead of
falling through to network. Block navigation outside the private origin.

- [ ] **Step 4: Add strict CSP**

Add a CSP meta element allowing the packaged paginator script, required reader
styles, and private reader images/fonts; set `connect-src`, `frame-src`,
`object-src`, `form-action`, and `base-uri` to `'none'`.

- [ ] **Step 5: Run reader tests and commit**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*reader*'
git add gradle androidApp
git commit -m "fix(reader): isolate epub resources in webview"
```

---

### Task 6: Stream and archive limits

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/io/LimitedStreams.kt`
- Create corresponding unit test
- Modify: `AuthenticatedDataSourceFactory.kt`
- Modify: `ReaderFileCache.kt`
- Modify: `EpubBook.kt`
- Modify their tests

- [ ] **Step 1: Write exact-boundary failing tests**

Test `limit` bytes succeeds and `limit + 1` throws `ContentLimitExceeded`.
Create ZIPs exceeding entry count, per-entry bytes, total bytes, and 200:1
ratio. Assert partial files/directories are absent.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :android-shared:testDebugUnitTest :androidApp:testDebugUnitTest \
  --tests '*LimitedStreamsTest' --tests '*ReaderFileCacheTest' --tests '*EpubBookTest'
```

- [ ] **Step 3: Implement bounded copy primitives**

```kotlin
fun InputStream.copyToLimited(out: OutputStream, maxBytes: Long): Long {
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
        if (total > maxBytes) throw ContentLimitExceeded(maxBytes)
        out.write(buffer, 0, read)
    }
}
```

Apply 32 MiB subtitles, 2 GiB reader input, 20,000 ZIP entries, 512 MiB per
entry, 2 GiB total output, and 200:1 ratio. Check declared sizes early and
streamed bytes authoritatively.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :android-shared:testDebugUnitTest :androidApp:testDebugUnitTest
git add android-shared androidApp
git commit -m "fix(reader): bound remote and archive content"
```

---

### Task 7: Contained persistent path segments

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/store/SafePathSegment.kt`
- Create unit test
- Modify: `DownloadStorage.kt`
- Modify: `ScopedJsonFileStore.kt`
- Modify their tests

- [ ] **Step 1: Write failing traversal/compatibility tests**

```kotlin
assertEquals("550e8400-e29b-41d4-a716-446655440000", safePathSegment(uuid))
assertNotEquals("../other", safePathSegment("../other"))
assertTrue(containedChild(root, "../other") == null)
```

Assert recursive profile/server deletion cannot touch a sentinel outside root.
Also assert MediaStore paths encode unsafe segments and prefix-delete queries
escape `%`, `_`, and the escape character.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*DownloadStorageTest' --tests '*ScopedJsonFileStoreTest' --tests '*SafePathSegmentTest'
```

- [ ] **Step 3: Implement codec and containment**

Keep `[A-Za-z0-9_-][A-Za-z0-9._-]{0,119}` unchanged except `.`/`..`.
Otherwise encode `~` plus URL-safe Base64 without padding; hash overlong
encoded values. Every read/write/delete uses:

```kotlin
fun containedChild(root: File, vararg segments: String): File? {
    val canonicalRoot = root.canonicalFile
    val child = segments.fold(canonicalRoot) { parent, value ->
        File(parent, safePathSegment(value))
    }.canonicalFile
    return child.takeIf {
        it.path == canonicalRoot.path ||
            it.path.startsWith(canonicalRoot.path + File.separator)
    }
}
```

Probe safe legacy paths only when their canonical path remains contained.
When an encoded target does not exist but a contained legacy target does, read
the legacy value and write the next update to the encoded target. Delete both
contained candidates after successful migration.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :android-shared:testDebugUnitTest
git add android-shared
git commit -m "fix(storage): contain identity scoped paths"
```

---

### Task 8: Protect custom PiP service actions

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PipActionCapability.kt`
- Modify: PiP action PendingIntent creation
- Modify: `SiloPlaybackService.kt`
- Create/modify playback service tests

- [ ] **Step 1: Write failing forged-intent test**

Assert `ACTION_PIP_PLAY` without the current capability produces no player
call; the same action built by `PipActionCapability.intent(...)` succeeds.
Assert rotation invalidates an earlier intent.
Retain a source/connection test proving the MediaSession service remains
available to trusted system controllers.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :android-shared:testDebugUnitTest --tests '*PipAction*Test'
```

- [ ] **Step 3: Implement process capability**

Generate 32 random bytes with `SecureRandom`, keep them process-only, encode
Base64URL into an explicit extra, and verify decoded bytes using
`MessageDigest.isEqual`. Unknown/missing/stale tokens return `false` without
touching the player.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :android-shared:testDebugUnitTest
git add android-shared androidApp androidTvApp
git commit -m "fix(player): authenticate custom pip actions"
```

---

### Task 9: Correct Dolby Vision manifest and assert merged permissions

**Files:**
- Modify: `scripts/build-dovi-aar.sh`
- Replace: `android-shared/libs/silo-dovi-bridge-2.3.1.aar`
- Modify: phone/TV manifest policy tests
- Create: `scripts/check-merged-manifest-permissions.sh`

- [ ] **Step 1: Write failing merged-manifest check**

The script processes manifests and fails on `READ_PHONE_STATE`,
`READ_EXTERNAL_STORAGE`, or TV `WRITE_EXTERNAL_STORAGE`; it permits only phone
`WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28`.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :androidApp:processDebugMainManifest :androidTvApp:processDebugMainManifest
./scripts/check-merged-manifest-permissions.sh debug
```

Expected: FAIL on permissions implied by the AAR.

- [ ] **Step 3: Emit modern uses-sdk and rebuild**

Generate:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />
</manifest>
```

Rebuild all ABIs with the pinned toolchain.

- [ ] **Step 4: Verify and commit**

```bash
./scripts/build-dovi-aar.sh
./gradlew :androidApp:processDebugMainManifest :androidTvApp:processDebugMainManifest
./scripts/check-merged-manifest-permissions.sh debug
git add scripts android-shared androidApp androidTvApp
git commit -m "fix(android): remove implicit legacy permissions"
```

---

### Task 10: Pin CI and Gradle dependency inputs

**Files:**
- Modify: `.github/workflows/android-build.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/verification-metadata.xml`
- Modify: root build settings for dependency locking
- Create generated lockfiles

- [ ] **Step 1: Add failing policy script/test**

Create `scripts/check-build-supply-chain.sh` that rejects `uses:` entries not
pinned to 40-character SHAs, a missing wrapper checksum, or missing dependency
verification metadata.

- [ ] **Step 2: Run and verify RED**

```bash
./scripts/check-build-supply-chain.sh
```

- [ ] **Step 3: Pin Actions and wrapper**

Resolve each currently used release tag to its verified upstream commit and
write:

```yaml
uses: actions/checkout@<40-char-sha> # v6
```

Add the official Gradle 8.12 binary distribution SHA-256.

- [ ] **Step 4: Generate verification metadata and locks**

```bash
./gradlew --write-verification-metadata sha256 \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleDebug \
  :androidTvApp:assembleDebug
./gradlew resolveAndLockAll --write-locks
```

Add a root `resolveAndLockAll` task that resolves every resolvable configuration
in every project after enabling `dependencyLocking { lockAllConfigurations() }`.
Review the generated lock state for unexpected repositories/artifacts.

- [ ] **Step 5: Verify and commit**

```bash
./scripts/check-build-supply-chain.sh
./gradlew --dependency-verification=strict help
git add .github gradle gradle.properties settings.gradle.kts build.gradle.kts \
  '*.lockfile' scripts/check-build-supply-chain.sh
git commit -m "build(android): verify release dependencies"
```

---

### Task 11: Build libdovi from pinned upstream source

**Files:**
- Modify: `scripts/build-dovi-aar.sh`
- Create: `android-shared/src/native/dovi/provenance.json`
- Create: `scripts/check-rust-osv.mjs`
- Update third-party notices

- [ ] **Step 1: Record current artifact and add failing provenance check**

Require source repo, commit, archive checksum, Rust version, NDK version,
per-ABI output hashes, and final AAR hash. The check must fail while the build
still consumes `edde746/libdovi-builds` binaries.

- [ ] **Step 2: Run and verify RED**

```bash
./scripts/build-dovi-aar.sh --verify-provenance
```

- [ ] **Step 3: Replace binary download with source build**

Download the pinned `quietvoid/dovi_tool` source archive, verify its SHA-256,
verify `Cargo.lock`, install no unpinned tooling, and build the `dolby_vision`
static library for the three Android targets with the recorded Rust/NDK
toolchains. Link the existing JNI wrapper.

- [ ] **Step 4: Add crates.io OSV check**

Parse the pinned `Cargo.lock`, submit `{ecosystem:"crates.io",name,version}` to
OSV `querybatch`, fail on affected results, and print package names only.

- [ ] **Step 5: Verify reproducibility and commit**

Build twice in separate temporary directories and compare unzipped entry
hashes, ignoring ZIP timestamps only if the archive writer cannot normalize
them.

```bash
node scripts/check-rust-osv.mjs < upstream-Cargo.lock
./scripts/build-dovi-aar.sh
./scripts/build-dovi-aar.sh --verify-provenance
git add scripts android-shared/src/native/dovi android-shared/libs
git commit -m "build(android): pin native playback provenance"
```

---

### Task 12: Full security verification and PR preparation

**Files:**
- No production changes unless verification exposes a defect.

- [ ] **Step 1: Verify SiloCast is untouched**

```bash
git diff --name-only perf/android-client-performance-stability-v2..HEAD \
  | rg -i 'silocast|_silocast' && exit 1 || true
```

- [ ] **Step 2: Run all unit suites**

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest
```

- [ ] **Step 3: Run lint, manifests, and assemblies**

```bash
./gradlew \
  :androidApp:lintDebug \
  :androidTvApp:lintDebug \
  :androidApp:processReleaseMainManifest \
  :androidTvApp:processReleaseMainManifest \
  :androidApp:assembleDebug \
  :androidTvApp:assembleDebug
./scripts/check-merged-manifest-permissions.sh release
```

- [ ] **Step 4: Re-run supply-chain and secret checks**

```bash
./scripts/check-build-supply-chain.sh
node scripts/check-rust-osv.mjs < upstream-Cargo.lock
rg -n --hidden -g '!**/build/**' \
  '(BEGIN (RSA|EC|OPENSSH) PRIVATE KEY|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{30,})' .
```

Expected: policy checks pass and secret scan returns no matches.

- [ ] **Step 5: Compare against the approved finding list**

Confirm tests exist for origin leakage, HTTP downgrade, WebView/file access,
resource limits, path containment, forged PiP actions, implicit permissions,
mutable Actions, wrapper/dependency verification, and native provenance.

- [ ] **Step 6: Confirm clean worktree and reviewable history**

```bash
git status --short
git log --oneline perf/android-client-performance-stability-v2..HEAD
```

Expected: clean status and the security commits from this plan only.

- [ ] **Step 7: Request code review before pushing**

Use `superpowers:requesting-code-review`. Address confirmed findings, rerun the
affected focused tests, then repeat Steps 1–6 before creating the single
security PR.
