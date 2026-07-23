package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity

private const val SUBTITLE_ARTIFACT_TRACK_ID_PREFIX = "silo-subtitle:"
private const val DOWNLOADED_SUBTITLE_ARTIFACT_TRACK_ID_PREFIX =
    "silo-downloaded-subtitle:"

/**
 * Stable Media3 identity for a server-authored subtitle artifact.
 *
 * The index is the server's combined subtitle index, not a Media3 ordinal.
 */
fun subtitleArtifactTrackId(serverIndex: Int): String =
    "$SUBTITLE_ARTIFACT_TRACK_ID_PREFIX$serverIndex"

fun downloadedSubtitleArtifactTrackId(artifactIndex: Int): String =
    "$DOWNLOADED_SUBTITLE_ARTIFACT_TRACK_ID_PREFIX$artifactIndex"

/**
 * Complete Media3 subtitle metadata retained by both phone and TV adapters.
 */
data class MountedSubtitleTrack(
    val index: Int,
    val trackId: String?,
    val label: String?,
    val language: String?,
    val codec: String?,
    val forced: Boolean?,
    val hearingImpaired: Boolean?,
)

data class MountedSubtitleMatch(
    val track: MountedSubtitleTrack,
)

/**
 * Resolves a committed typed identity against a mounted Media3 track snapshot.
 *
 * Exact IDs are checked across the complete snapshot before any metadata
 * fallback. Server sidecars are exact-ID only. Other identity types may use a
 * complete typed metadata fallback, but may never fall through to a
 * server-authored sidecar ID.
 */
fun resolveMountedSubtitle(
    identity: SubtitleIdentity,
    tracks: List<MountedSubtitleTrack>,
): MountedSubtitleMatch? {
    val expectedTrackId = identity.expectedMediaTrackId()
    if (expectedTrackId != null) {
        tracks.firstOrNull { it.trackId == expectedTrackId }
            ?.let { return MountedSubtitleMatch(it) }
    }

    val media = identity.fallbackMediaIdentity() ?: return null
    if (!media.hasTypedFallback()) return null

    val typedMatches = tracks
        .asSequence()
        .filterNot { it.hasReservedArtifactId() }
        .filter { it.matchesTypedMetadata(media) }
        .toList()
    if (typedMatches.isEmpty()) return null

    val targetLabel = normalizedLabel(media.label)
    val labelMatches = if (targetLabel == null) {
        emptyList()
    } else {
        typedMatches.filter { normalizedLabel(it.label) == targetLabel }
    }
    return when {
        labelMatches.size == 1 -> MountedSubtitleMatch(labelMatches.single())
        typedMatches.size == 1 -> MountedSubtitleMatch(typedMatches.single())
        else -> null
    }
}

/**
 * Bridges current catalog rows to typed identity. Both phone and TV use this
 * while the coordinator adapters still receive [PlayerSubtitleInfo].
 */
fun resolveMountedSubtitle(
    subtitle: PlayerSubtitleInfo,
    tracks: List<MountedSubtitleTrack>,
): MountedSubtitleMatch? {
    val explicitSource = subtitle.effectiveSubtitleSource()
    val isEmbedded = when {
        explicitSource.equals("embedded", ignoreCase = true) -> true
        explicitSource != null -> false
        else -> subtitle.url.isBlank()
    }
    if (!isEmbedded) {
        val exactIdentity = if (subtitle.isDownloadedSubtitleArtifact()) {
            SubtitleIdentity.Downloaded(
                downloadId = subtitle.index,
                media = SubtitleMediaIdentity(
                    trackId = downloadedSubtitleArtifactTrackId(subtitle.index),
                ),
            )
        } else {
            SubtitleIdentity.ServerSidecar(subtitle.index)
        }
        resolveMountedSubtitle(exactIdentity, tracks)
            ?.let { return it }
        if (subtitle.url.isBlank()) return null
        if (tracks.any(MountedSubtitleTrack::hasReservedArtifactId)) return null
    }

    val codecFamilies = listOfNotNull(
        subtitle.url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .takeIf(String::isNotBlank),
        subtitle.codec,
    ).distinct()
    val familiesToTry: List<String?> =
        if (codecFamilies.isEmpty()) listOf(null) else codecFamilies
    for (codec in familiesToTry) {
        val media = SubtitleMediaIdentity(
            label = subtitle.label,
            language = subtitle.language,
            codecFamily = codec,
            forced = subtitle.forced,
            hearingImpaired = subtitle.label
                ?.takeIf(::subtitleLabelIndicatesHearingImpaired)
                ?.let { true },
        )
        val identity = when {
            isEmbedded -> SubtitleIdentity.Embedded(subtitle.index, media)
            subtitle.isDownloadedSubtitleArtifact() -> return null
            else -> SubtitleIdentity.LocalMedia3(media)
        }
        resolveMountedSubtitle(identity, tracks)?.let { return it }
    }
    return null
}

private fun SubtitleIdentity.expectedMediaTrackId(): String? = when (this) {
    is SubtitleIdentity.ServerSidecar -> subtitleArtifactTrackId(serverIndex)
    is SubtitleIdentity.Embedded -> media.trackId.normalizedNonServerTrackId()
    is SubtitleIdentity.Downloaded -> media.trackId.normalizedDownloadedTrackId()
    is SubtitleIdentity.LocalMedia3 -> media.trackId.normalizedNonServerTrackId()
    SubtitleIdentity.Off,
    is SubtitleIdentity.ServerBurnIn,
    -> null
}

private fun SubtitleIdentity.fallbackMediaIdentity(): SubtitleMediaIdentity? = when (this) {
    is SubtitleIdentity.Embedded -> media.takeUnless { it.trackId.isReservedArtifactTrackId() }
    is SubtitleIdentity.LocalMedia3 -> media.takeUnless { it.trackId.isReservedArtifactTrackId() }
    SubtitleIdentity.Off,
    is SubtitleIdentity.ServerSidecar,
    is SubtitleIdentity.ServerBurnIn,
    is SubtitleIdentity.Downloaded,
    -> null
}

private fun SubtitleMediaIdentity.hasTypedFallback(): Boolean =
    normalizedLanguage(language) != null ||
        normalizedSubtitleCodecFamily(codecFamily) != null ||
        forced != null ||
        hearingImpaired != null

private fun MountedSubtitleTrack.matchesTypedMetadata(identity: SubtitleMediaIdentity): Boolean {
    val targetLanguage = normalizedLanguage(identity.language)
    val targetCodec = normalizedSubtitleCodecFamily(identity.codecFamily)

    if (targetLanguage != null && normalizedLanguage(language) != targetLanguage) return false
    if (targetCodec != null && normalizedSubtitleCodecFamily(codec) != targetCodec) return false
    if (identity.forced != null && forced != identity.forced) return false
    if (identity.hearingImpaired != null && hearingImpaired != identity.hearingImpaired) return false
    return true
}

private fun MountedSubtitleTrack.hasReservedArtifactId(): Boolean =
    trackId.isReservedArtifactTrackId()

private fun String?.normalizedValue(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.normalizedNonServerTrackId(): String? =
    normalizedValue()?.takeUnless(String::isReservedArtifactTrackId)

private fun String?.normalizedDownloadedTrackId(): String? =
    normalizedValue()
        ?.takeIf { it.startsWith(DOWNLOADED_SUBTITLE_ARTIFACT_TRACK_ID_PREFIX) }

private fun String?.isReservedArtifactTrackId(): Boolean =
    this?.startsWith(SUBTITLE_ARTIFACT_TRACK_ID_PREFIX) == true ||
        this?.startsWith(DOWNLOADED_SUBTITLE_ARTIFACT_TRACK_ID_PREFIX) == true

internal fun PlayerSubtitleInfo.isDownloadedSubtitleArtifact(): Boolean =
    source.normalizedValue().equals("downloaded", ignoreCase = true) ||
        catalogSource.normalizedValue().equals("downloaded", ignoreCase = true)

private fun PlayerSubtitleInfo.effectiveSubtitleSource(): String? =
    source.normalizedValue() ?: catalogSource.normalizedValue()

private fun normalizedLabel(label: String?): String? =
    label.normalizedValue()?.lowercase()

private fun normalizedLanguage(language: String?): String? {
    val primary = language
        .normalizedValue()
        ?.takeUnless { it.equals("und", ignoreCase = true) }
        ?.lowercase()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?: return null
    return when (primary) {
        "eng" -> "en"
        "spa" -> "es"
        "fre", "fra" -> "fr"
        "ger", "deu" -> "de"
        "dut", "nld" -> "nl"
        "jpn" -> "ja"
        "dan" -> "da"
        else -> primary
    }
}

internal fun normalizedSubtitleCodecFamily(codecOrMime: String?): String? {
    val normalized = codecOrMime
        .normalizedValue()
        ?.filter(Char::isLetterOrDigit)
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    return when {
        normalized.contains("pgs") -> "pgs"
        normalized.contains("vobsub") || normalized.contains("dvdsubtitle") -> "vobsub"
        normalized.contains("dvbsub") -> "dvbsub"
        normalized.contains("subrip") || normalized.endsWith("srt") -> "subrip"
        normalized.contains("webvtt") || normalized == "textvtt" || normalized.endsWith("vtt") -> "webvtt"
        normalized.contains("tx3g") || normalized.contains("movtext") -> "tx3g"
        normalized.contains("ssa") || normalized == "ass" -> "ssa"
        normalized.contains("ttml") -> "ttml"
        normalized.contains("cea608") || normalized.contains("eia608") -> "cea608"
        normalized.contains("cea708") -> "cea708"
        else -> normalized
    }
}

internal fun subtitleLabelIndicatesHearingImpaired(label: String?): Boolean {
    val value = label?.lowercase() ?: return false
    if (
        value.contains("closed caption") ||
        value.contains("hearing impaired") ||
        value.contains("hearing-impaired")
    ) {
        return true
    }
    return Regex("""(^|[^a-z0-9])(cc|sdh|hi)([^a-z0-9]|$)""").containsMatchIn(value)
}
