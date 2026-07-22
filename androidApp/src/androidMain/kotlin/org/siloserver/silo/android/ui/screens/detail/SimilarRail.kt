package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.RecommendationRepository
import org.koin.compose.koinInject
import org.siloserver.silo.util.mapConcurrentBounded

/**
 * "More Like This" section — header plus a horizontal poster rail —
 * shown at the bottom of Movie / Series detail pages. Mirrors
 * `PhoneSimilarRail.swift`:
 *   1. Hit `/recommendations/similar/{contentId}` for scored IDs
 *   2. Resolve each ID to an `ItemDetail` in parallel
 *   3. Render a poster card per resolved item; tap opens detail
 *
 * The whole section (header included) stays hidden until the request
 * resolves with items — servers without media embeddings return an
 * empty/failed response, and an orphaned header would just read as a
 * broken row.
 */
@Composable
fun SimilarRail(
    contentId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    recommendationRepository: RecommendationRepository = koinInject(),
    catalogRepository: CatalogRepository = koinInject(),
) {
    var items by remember(contentId) { mutableStateOf<List<ItemDetail>>(emptyList()) }
    var routeActive by remember(contentId) { mutableStateOf(false) }

    LifecycleResumeEffect(contentId) {
        routeActive = true
        onPauseOrDispose { routeActive = false }
    }

    LaunchedEffect(contentId, routeActive) {
        items = emptyList()
        if (!routeActive) return@LaunchedEffect
        delay(300)
        val loaded = loadSimilar(contentId, recommendationRepository, catalogRepository)
        if (routeActive) items = loaded
    }

    if (items.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier,
        ) {
            SectionHeader(title = "More Like This")
            SimilarRailContent(items = items, onSelect = onSelect)
        }
    }
}

private suspend fun loadSimilar(
    contentId: String,
    recommendationRepository: RecommendationRepository,
    catalogRepository: CatalogRepository,
): List<ItemDetail> {
    val scored = when (val res = recommendationRepository.getSimilar(contentId, limit = 12)) {
        is ApiResult.Success -> res.data.items
        else -> return emptyList()
    }
    if (scored.isEmpty()) return emptyList()

    // Resolve detail pages with bounded concurrency, preserving engine ranking
    // while dropping failed lookups without blocking primary detail content.
    return scored.mapConcurrentBounded(maxConcurrency = 3) { ref ->
        (catalogRepository.getItemDetail(ref.mediaItemId) as? ApiResult.Success)?.data
    }.filterNotNull()
}

@Composable
private fun SimilarRailContent(
    items: List<ItemDetail>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = SafePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items,
            key = { it.contentId },
            contentType = { "similar-item" },
        ) { item ->
            MediaCard(
                title = item.title,
                posterUrl = item.posterUrl,
                posterThumbhash = item.posterThumbhash,
                year = item.year.takeIf { it > 0 },
                type = item.type,
                userState = null,
                progress = null,
                onClick = { onSelect(item.contentId) },
                overlay = org.siloserver.silo.overlays.OverlayDataExtractor.fromItemDetail(item),
                sharedContentId = item.contentId,
            )
        }
    }
}
