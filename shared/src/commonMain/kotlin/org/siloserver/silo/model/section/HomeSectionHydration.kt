package org.siloserver.silo.model.section

/**
 * Normalizes the two Home-section item response shapes supported by the server.
 * Populated sibling items take precedence over default-zero section metadata;
 * otherwise an explicitly empty section remains a successful empty result.
 */
fun resolveHomeSectionItems(
    originalSection: ResolvedSection,
    response: HomeSectionItemsResponse,
): ResolvedSection? {
    val responseSection = response.section
    return when {
        responseSection != null && responseSection.items.isNotEmpty() -> responseSection
        responseSection != null && response.items.isNotEmpty() -> responseSection.copy(items = response.items)
        response.items.isNotEmpty() -> originalSection.copy(items = response.items)
        responseSection != null && responseSection.totalCount == 0 -> responseSection
        else -> null
    }
}
