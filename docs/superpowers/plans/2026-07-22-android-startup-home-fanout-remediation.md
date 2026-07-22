# Android Startup Home Fan-out Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop Android TV and Android mobile startup warmup from re-fetching every Home section while retaining bounded compatibility hydration for older servers.

**Architecture:** Add a testable startup-hydration helper beside `warmHome` in the shared Android startup module. It consumes the already-fetched sections, resolves only declared-but-empty sections through a four-request bounded fallback, and returns ordered resolved sections plus a cache-safety flag. Both application activities already call this shared warmup and require only regression guards, not platform-specific behavior.

**Tech Stack:** Kotlin Multiplatform, coroutines, `mapConcurrentBounded`, `ApiResult`, kotlin-test/JUnit 4, Gradle Android application modules.

## Global Constraints

- Apply the behavior to both `androidApp` and `androidTvApp` through `android-shared`.
- Modern inline Home responses must produce zero per-section warmup requests.
- Older-server fallback must run with `maxConcurrency = 4`.
- Preserve original section order and support nested-section and top-level-items fallback response shapes.
- Never replace the Home cache with a partial hydration result.
- Keep warmup best-effort and off the routing/splash critical path.
- Do not install or launch either application during implementation verification unless separately requested.

---

### Task 1: Specify startup Home hydration behavior

**Files:**
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/startup/StartupHomeHydrationTest.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/startup/StartupWarmup.kt`

**Interfaces:**
- Consumes: `ResolvedSection`, `HomeSectionItemsResponse`, `ApiResult`, and the existing `mapConcurrentBounded` coroutine helper.
- Produces: `StartupHomeResolution(sections: List<ResolvedSection>, fullyResolved: Boolean)` and `hydrateStartupHomeSections(sections, fetchItems)`.

- [ ] **Step 1: Write the failing behavioral tests**

Create `StartupHomeHydrationTest.kt` with tests that invoke the not-yet-existing helper:

```kotlin
package org.siloserver.silo.common.startup

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult

class StartupHomeHydrationTest {
    @Test
    fun inlineAndEmptySectionsDoNotTriggerFallback() = runTest {
        val calls = mutableListOf<String>()
        val result = hydrateStartupHomeSections(
            sections = listOf(
                section("inline", totalCount = 1, items = listOf(item("one"))),
                section("empty", totalCount = 0),
            ),
            fetchItems = { id ->
                calls += id
                ApiResult.Error(500, "unexpected", "unexpected fallback")
            },
        )

        assertTrue(result.fullyResolved)
        assertEquals(emptyList(), calls)
        assertEquals(listOf("inline"), result.sections.map { it.id })
    }

    @Test
    fun fallbackSupportsBothResponseShapesAndPreservesOrder() = runTest {
        val result = hydrateStartupHomeSections(
            sections = listOf(
                section("nested", totalCount = 1),
                section("inline", totalCount = 1, items = listOf(item("inline-item"))),
                section("top-level", totalCount = 1),
            ),
            fetchItems = { id ->
                when (id) {
                    "nested" -> ApiResult.Success(
                        HomeSectionItemsResponse(
                            section = section("nested", totalCount = 1, items = listOf(item("nested-item"))),
                        ),
                    )
                    "top-level" -> ApiResult.Success(
                        HomeSectionItemsResponse(items = listOf(item("top-level-item"))),
                    )
                    else -> error("unexpected fallback for $id")
                }
            },
        )

        assertTrue(result.fullyResolved)
        assertEquals(listOf("nested", "inline", "top-level"), result.sections.map { it.id })
        assertEquals("top-level-item", result.sections.last().items.single().contentId)
    }

    @Test
    fun fallbackConcurrencyIsBoundedToFour() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = hydrateStartupHomeSections(
            sections = (1..12).map { section("section-$it", totalCount = 1) },
            fetchItems = { id ->
                val now = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                delay(10)
                active.decrementAndGet()
                ApiResult.Success(HomeSectionItemsResponse(items = listOf(item("item-$id"))))
            },
        )

        assertTrue(result.fullyResolved)
        assertEquals(4, peak.get())
    }

    @Test
    fun failedFallbackMarksResultPartial() = runTest {
        val result = hydrateStartupHomeSections(
            sections = listOf(
                section("good", totalCount = 1, items = listOf(item("good-item"))),
                section("failed", totalCount = 1),
            ),
            fetchItems = { ApiResult.NetworkError(IllegalStateException("offline")) },
        )

        assertFalse(result.fullyResolved)
        assertEquals(listOf("good"), result.sections.map { it.id })
    }

    private fun section(
        id: String,
        totalCount: Int,
        items: List<SectionItem> = emptyList(),
    ) = ResolvedSection(id = id, sectionType = "test", title = id, totalCount = totalCount, items = items)

    private fun item(id: String) = SectionItem(contentId = id, type = "movie", title = id)
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest --tests '*StartupHomeHydrationTest'
```

Expected: compilation fails because `hydrateStartupHomeSections` does not exist.

- [ ] **Step 3: Implement the minimal hydration helper**

In `StartupWarmup.kt`, add:

```kotlin
internal data class StartupHomeResolution(
    val sections: List<ResolvedSection>,
    val fullyResolved: Boolean,
)

internal suspend fun hydrateStartupHomeSections(
    sections: List<ResolvedSection>,
    fetchItems: suspend (String) -> ApiResult<HomeSectionItemsResponse>,
): StartupHomeResolution {
    val unresolved = sections.filter { it.items.isEmpty() && it.totalCount > 0 }
    val fallbackById = unresolved.mapConcurrentBounded(maxConcurrency = 4) { section ->
        val hydrated = when (val result = fetchItems(section.id)) {
            is ApiResult.Success -> {
                val responseSection = result.data.section
                when {
                    responseSection != null && responseSection.items.isNotEmpty() -> responseSection
                    responseSection != null && result.data.items.isNotEmpty() ->
                        responseSection.copy(items = result.data.items)
                    result.data.items.isNotEmpty() -> section.copy(items = result.data.items)
                    responseSection != null && responseSection.totalCount == 0 -> responseSection
                    else -> null
                }
            }
            is ApiResult.Error,
            is ApiResult.NetworkError -> null
        }
        section.id to hydrated
    }.toMap()

    val fullyResolved = unresolved.all { fallbackById[it.id] != null }
    val resolved = sections.mapNotNull { section ->
        when {
            section.items.isNotEmpty() -> section
            section.totalCount == 0 -> null
            else -> fallbackById[section.id]?.takeIf { it.items.isNotEmpty() }
        }
    }
    return StartupHomeResolution(resolved, fullyResolved)
}
```

Add imports for `HomeSectionItemsResponse` and `mapConcurrentBounded`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2. Expected: `BUILD SUCCESSFUL` and all four tests pass.

- [ ] **Step 5: Commit the behavioral unit**

```bash
git add android-shared/src/androidMain/kotlin/org/siloserver/silo/common/startup/StartupWarmup.kt android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/startup/StartupHomeHydrationTest.kt
git commit -m "perf(android): bound startup home hydration"
```

---

### Task 2: Route startup warmup through the bounded helper

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/startup/StartupWarmup.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/SharedStartupWarmupSourceTest.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/SharedStartupWarmupSourceTest.kt`

**Interfaces:**
- Consumes: `hydrateStartupHomeSections` from Task 1 and the existing `SectionRepository.getHomeSectionItems` callback.
- Produces: cache/artwork warmup with no modern-server section fan-out, shared by both activities.

- [ ] **Step 1: Add mobile and TV ownership regression guards**

Each module test reads its `MainActivity` source and asserts it calls `warmAuthenticatedStartup`; it also asserts the platform activity contains no `getHomeSectionItems` call. Use the module-local source-test pattern already present in both applications.

- [ ] **Step 2: Replace unconditional `async`/`awaitAll` hydration**

Replace `warmHome`'s `result.data.sections.map { async { ... } }.awaitAll()` block with:

```kotlin
val resolution = hydrateStartupHomeSections(result.data.sections) { sectionId ->
    sectionRepository.getHomeSectionItems(sectionId)
}
if (resolution.fullyResolved && resolution.sections.isNotEmpty()) {
    homeCache.cacheHome(resolution.sections)
    warmHomeArtwork(context, resolution.sections, artworkPlan)
}
```

Remove coroutine imports that are no longer used by `warmHome`; retain those still used by `warmAuthenticatedStartup`.

- [ ] **Step 3: Run focused shared/mobile/TV tests**

```bash
./gradlew :android-shared:testDebugUnitTest --tests '*StartupHomeHydrationTest' :androidApp:testDebugUnitTest --tests '*SharedStartupWarmupSourceTest' :androidTvApp:testDebugUnitTest --tests '*SharedStartupWarmupSourceTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Audit both platform trees for competing fan-outs**

```bash
rg -n 'getHomeSectionItems|home/sections.*items|awaitAll' androidApp/src/androidMain androidTvApp/src/androidMain android-shared/src/androidMain
```

Expected: no platform-local Home startup hydration; the only startup fallback is the bounded shared helper. Any non-Home `awaitAll` call is reviewed but left unchanged unless it reproduces this same Home-section issue.

- [ ] **Step 5: Commit shared platform wiring**

```bash
git add android-shared/src/androidMain/kotlin/org/siloserver/silo/common/startup/StartupWarmup.kt androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/SharedStartupWarmupSourceTest.kt androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/SharedStartupWarmupSourceTest.kt
git commit -m "test(android): guard shared startup home warmup"
```

---

### Task 3: Verify both Android form factors

**Files:**
- Verify only; no production files should change.

**Interfaces:**
- Consumes: the completed shared warmup implementation.
- Produces: test/build evidence for Android mobile and Android TV.

- [ ] **Step 1: Check formatting and worktree scope**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and no unrelated changes.

- [ ] **Step 2: Run the complete shared and application verification gate**

```bash
./gradlew :shared:test :android-shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidTvApp:testDebugUnitTest :androidApp:assembleDebug :androidTvApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Review the final diff and commit history**

```bash
git diff --check
git status --short
git log --oneline -8
```

Expected: clean worktree with the design, implementation-plan, hydration, and platform-guard commits present.

- [ ] **Step 4: Report the observable comparison target**

Record that a follow-up TV or mobile diagnostics bundle should contain zero startup `/api/v1/home/sections/{id}/items` requests when `/home/sections` is fully hydrated, while legacy responses may show no more than four active fallbacks at once.
