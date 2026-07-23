# Android Project Security Hardening Design

**Date:** 2026-07-23
**Status:** Approved
**Target branch:** `security/android-project-hardening`
**Stacking base:** corrected `perf/android-client-performance-stability-v2`

## Objective

Remediate the confirmed Android phone and TV security findings without breaking
playback, reader behavior, self-hosted HTTP servers, existing downloaded data,
or trusted Android media integrations.

The performance corrections and security hardening remain separate pull
requests. The security PR is one large PR stacked on the corrected performance
branch.

## Explicit Exclusion

SiloCast is excluded from this change.

The security PR must not change:

- the SiloCast protocol version or message schema;
- SiloCast TLS or PSK behavior;
- NSD discovery or advertisement fields;
- phone controller connection behavior;
- TV receiver authorization or controller replacement behavior; or
- remote-playback handoff behavior.

SiloCast authentication requires a separate compatibility and rollout design.

## Compatibility Requirements

1. Self-hosted HTTP servers remain supported.
2. Existing signed-in HTTP users are not logged out.
3. New or reauthenticated HTTP connections require explicit informed consent.
4. Absolute media, subtitle, and reader URLs remain supported.
5. Stream-scoped headers supplied explicitly by a playback plan remain
   supported for their intended request.
6. Silo access tokens, profile IDs, and profile tokens never cross the active
   Silo server origin.
7. Existing safe UUID-style download and local-state paths remain unchanged.
8. Existing valid downloaded media and local reader/audiobook state remain
   discoverable.
9. PiP controls, Bluetooth controls, lock-screen controls, Android system media
   controls, phone playback, and TV playback continue to work.
10. Reader pagination, typography controls, images, tables, SVG, MathML, ruby
    text, and internal navigation remain available.
11. No device installation or automatic app launch is part of implementation
    verification unless separately requested.

## Work Separation

### Performance PR

The performance branch is corrected before the security branch is rebased onto
it. That PR contains only:

- identity-bound final playback-position writes;
- Home cache identity validation on both cached and fetched results;
- removal of profile tokens from Home cache keys and bounded cache pruning; and
- correct TV playback-clock use after presentation-state separation.

### Security PR

The security branch contains every approved non-SiloCast security remediation:

- origin-scoped authentication;
- cleartext HTTP confirmation;
- reader WebView isolation and parser-backed sanitization;
- bounded reader, EPUB, and subtitle processing;
- contained path construction and compatibility migration;
- protected PiP service actions;
- Dolby Vision AAR manifest correction and permission assertions; and
- CI, Gradle, dependency, and native artifact supply-chain hardening.

## Architecture

### 1. Identity-bound performance writes

`FinalPlaybackPosition` carries an immutable identity scope containing the
server ID, profile ID, and identity generation captured at submission time.
The queue key includes that scope. The write callback uses a scoped repository
operation and rejects a write if its identity generation is no longer valid.
It never resolves the active identity at execution time.

`HomeRequestScope` contains identifiers and non-secret generation values only.
It does not contain access or profile tokens. The request gate validates scope:

- before returning a fresh cached result;
- after an in-flight request completes;
- before inserting a completed result into the cache; and
- again at the caller boundary before UI publication.

Expired entries and entries for superseded identity generations are physically
removed from both completed and in-flight maps.

### 2. TV playback-clock separation

TV presentation state represents structural UI data only. Playback position
and duration live in `PlaybackClock`.

Mounting, subtitle refresh, relative seek, hold seek, HUD rendering, cast state,
and final persistence use either `PlaybackClock` or a direct Media3 controller
snapshot. No playback operation reads the zeroed clock fields from structural
presentation state.

### 3. Origin-scoped authentication

A single origin policy normalizes a URL into:

- lower-case scheme;
- IDNA-normalized lower-case host; and
- effective port, including the default port for HTTP or HTTPS.

An origin matches only when all three values match. User-info URLs and
non-HTTP(S) URLs never receive Silo HTTP credentials.

Every authentication path applies the same rule:

- Ktor API authentication plugin;
- OkHttp `MediaAuthInterceptor`;
- Media3 refreshable HTTP data source;
- reader remote-file requests;
- subtitle sidecar requests; and
- download requests.

Relative URLs are resolved against the captured active server before the policy
is evaluated. Absolute same-origin URLs receive the normal Silo session
headers. Absolute cross-origin URLs do not.

Explicit playback-plan headers are kept separate from Silo session headers.
They are attached only to the request target for which the plan supplied them.
Redirect follow-ups are tested so a cross-origin redirect cannot carry either
Silo session credentials or a target-scoped authorization header to a new
origin.

HTTP 401 refresh and retry occur only for same-origin Silo requests. A 401 from
a foreign origin must not trigger Silo token refresh.

### 4. Cleartext HTTP consent

HTTPS remains the first automatic probe.

If a bare host responds only over HTTP, setup stops before saving the server or
navigating to login. Phone and TV display the normalized cleartext origin and
require a second explicit confirmation action. Explicit `http://` input follows
the same confirmation path.

Consent is stored per normalized server origin. It is not global. Changing
scheme, host, or effective port requires new consent.

Existing authenticated HTTP users continue their current session. They are
asked for confirmation only when they reconnect, reauthenticate, or replace the
saved server. No migration forces a logout.

Both manifests continue to permit cleartext transport because arbitrary
self-hosted HTTP origins cannot be enumerated in a static network-security
configuration. The application-level confirmation and origin-scoped credential
policy form the security boundary.

### 5. Reader WebView isolation

The reader uses stable `androidx.webkit:webkit:1.16.0`,
`org.jsoup:jsoup:1.22.2`, and `WebViewAssetLoader`.

The trusted reader shell and extracted EPUB resources are served from a private
HTTPS-style application origin. All requested EPUB paths are canonicalized and
must remain under the selected extraction root.

The WebView configuration:

- enables JavaScript only for the trusted pagination shell;
- disables file access;
- disables content access;
- disables file access from file origins;
- disables universal access from file origins;
- blocks navigation outside the private reader origin;
- blocks unhandled network requests; and
- keeps the existing narrow `AndroidReflow.onEvent` bridge.

The shell declares a Content Security Policy that permits only the bundled
paginator script, required inline reader styles, and images/fonts from the
private reader resource path. It denies frames, forms, objects, connections,
remote media, and all other script sources.

EPUB chapter HTML is parsed into a document tree and cleaned with an explicit
allowlist. Regexes may remain as fast prefilters but are not the security
boundary. The allowlist preserves ordinary semantic book content while
removing:

- scripts and executable elements;
- event attributes;
- forms and interactive controls;
- frames, objects, and embeds;
- `srcdoc`, external stylesheets, publisher style blocks, and inline styles;
- absolute, protocol-relative, data, JavaScript, and other active URLs; and
- base elements capable of changing the resource origin.

### 6. Bounded content processing

Limits are enforced while streaming, even when `Content-Length` is absent or
incorrect.

| Content | Limit |
| --- | ---: |
| Subtitle sidecar buffered for normalization | 32 MiB |
| Remote reader source | 2 GiB |
| EPUB entry count | 20,000 |
| EPUB single uncompressed entry | 512 MiB |
| EPUB total extracted output | 2 GiB |
| EPUB compression ratio | 200:1 |

Limit violations close streams, delete temporary or partial files, and return a
normal domain error indicating that the file is too large or unsafe. They do
not leave cache entries that can be mistaken for valid content.

ZIP-slip containment remains in place. Entry count, per-entry bytes, total
bytes, and compression ratio are checked in addition to canonical paths.

### 7. Safe persistent path segments

All server-, profile-, content-, and file-derived path segments go through one
path-segment codec.

- Existing safe segments matching the accepted UUID/alphanumeric form remain
  byte-for-byte unchanged.
- `"."`, `".."`, separators, control characters, empty values, overlong
  values, and platform-reserved forms are encoded to a deterministic
  URL-safe representation.
- Every constructed path is canonicalized and checked against its intended
  root before read, write, enumeration, or recursive deletion.

Reads and cleanup support both safe legacy paths and encoded paths only when
the candidate remains within the expected root. A successful legacy read is
migrated on the next write. Recursive cleanup never operates on an
uncontained path.

MediaStore relative paths use the same encoded segments and escaped query
values.

### 8. Protected PiP actions

`SiloPlaybackService` remains exported for Media3 system integration.

Custom PiP play, pause, and seek intents include a cryptographically random,
process-owned capability token. The service applies those actions only when:

- the action is one of the known PiP actions;
- the capability token matches the current process token using
  constant-time comparison; and
- an active session exists.

External explicit service intents without the capability are ignored. Media3
controller trust behavior remains compatible with system, Bluetooth,
lock-screen, and automotive controllers.

### 9. Dolby Vision manifest

The AAR build emits an explicit modern `<uses-sdk>` declaration aligned with
the application minimum and target SDK. The committed AAR is rebuilt from the
updated script.

Automated manifest tests inspect both phone and TV merged manifests and fail if
the Dolby Vision library reintroduces:

- `READ_PHONE_STATE`;
- `READ_EXTERNAL_STORAGE`; or
- an unbounded `WRITE_EXTERNAL_STORAGE`.

The phone's intentional Android 9-and-older
`WRITE_EXTERNAL_STORAGE(maxSdkVersion=28)` declaration remains until legacy
public-download storage is removed.

### 10. Supply-chain hardening

GitHub Actions are pinned to verified full commit SHAs with release tags
documented in comments. Release jobs retain least-privilege permissions and do
not expose secrets to pull-request workflows.

`gradle-wrapper.properties` includes the official SHA-256 checksum for the
pinned Gradle distribution. Gradle dependency verification metadata records
SHA-256 checksums for resolved build and runtime artifacts. Dependency locking
is enabled and lock state is committed for every resolvable configuration in
the phone, TV, shared, Android-shared, libass bridge, and baseline-profile
modules.

The Dolby Vision AAR is rebuilt from a pinned upstream libdovi source commit
instead of downloading opaque third-party static libraries. The build records:

- upstream libdovi version and source commit;
- source/archive URL;
- per-ABI input checksums;
- Android NDK version;
- Rust/native toolchain version where applicable; and
- final AAR checksum.

The build fails when an input differs from the recorded provenance. A checked-in
script submits every Rust package/version from the pinned upstream `Cargo.lock`
to OSV's crates.io ecosystem query and fails on an affected package. This native
check is separate from the Maven dependency scan. Third-party notices remain
packaged.

## Error Handling and Diagnostics

Security-policy failures use typed internal errors so UI code can distinguish:

- cleartext confirmation required;
- foreign-origin authentication suppressed;
- reader resource rejected;
- content limit exceeded;
- archive path rejected; and
- persistent path rejected.

User-facing errors are concise and actionable. Diagnostic events may record
the category, transport class, size bucket, and same-origin/cross-origin result.
They must not record:

- access or profile tokens;
- authorization or cookie values;
- full URLs or query strings;
- raw hostnames;
- filenames or document titles;
- document contents; or
- unencoded profile/content identifiers.

## Test Strategy

Implementation follows red-green-refactor. Each behavior begins with a focused
test that fails for the confirmed reason before production code changes.

Required automated coverage:

1. final playback writes cannot cross server, profile, or identity generation;
2. Home cached and fetched results reject stale identity;
3. Home cache keys contain no credential material and stale entries are pruned;
4. every TV duration consumer uses the playback clock or controller duration;
5. same-origin default-port normalization;
6. scheme, host, subdomain, user-info, and port mismatch handling;
7. relative URL authentication;
8. absolute foreign media, subtitle, reader, and download URLs receive no Silo
   credentials;
9. cross-origin redirects strip all sensitive headers and do not refresh Silo
   tokens;
10. HTTP fallback and explicit HTTP input require confirmation on phone and TV;
11. consent is scoped to the normalized origin;
12. malicious EPUB markup cannot execute script, invoke foreign resources, or
   escape the extraction root;
13. valid book layout elements and local resources still render;
14. each byte, entry-count, and compression-ratio limit fails at its boundary
   and deletes partial output;
15. safe legacy paths remain stable, unsafe paths encode deterministically, and
   recursive deletion cannot escape its root;
16. untrusted PiP service intents cannot control playback while valid PiP
   actions still work;
17. merged phone and TV manifests contain only intended permissions;
18. Gradle wrapper and dependency verification reject altered artifacts; and
19. native artifact inputs and output match recorded provenance.

Final verification runs:

- shared, Android shared, phone, and TV unit-test suites;
- phone and TV lint;
- debug and release manifest processing;
- phone and TV debug assembly;
- release compilation/assembly where signing configuration permits;
- dependency verification from a clean Gradle cache path where practical;
- secret scanning of the resulting diff; and
- a final comparison against the audit finding list.

## Acceptance Criteria

The work is complete when:

- the four performance blockers pass their regression tests on the performance
  branch;
- every approved non-SiloCast security finding has a passing regression test
  and implementation in the single security PR;
- SiloCast source and behavior are unchanged;
- no Silo credential can be sent to a foreign origin;
- HTTP remains usable only after informed confirmation;
- untrusted EPUB input cannot access app-private files or remote origins;
- content and archive processing is bounded;
- persistent paths and recursive deletion are contained;
- forged PiP service intents have no playback effect;
- obsolete implicit permissions are absent from both merged manifests;
- CI and Gradle artifacts are pinned and verified;
- all required verification passes; and
- the working trees are clean before PR creation.
