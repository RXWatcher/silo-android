package org.siloserver.silo.model.download

import org.siloserver.silo.model.catalog.VersionChapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Local-only on-disk record stored next to a downloaded media file at
 * `<filesDir>/downloads/<serverId>/<profileId>/<fileId>.record.json`.
 *
 * Unlike [DownloadRecord] (which is the wire shape — strictly mirrors the
 * server's `downloadResponse` struct and cannot grow new fields without
 * breaking the API contract), the sidecar is the client's full picture of
 * what's on disk: server state + the catalog metadata we need to render
 * the row offline. The whole reason it exists: without the catalog fields
 * stashed locally, an offline Downloads tab can show neither title nor
 * poster even when the bytes are sitting right there on disk.
 *
 * Written by [org.siloserver.silo.common.downloads.DownloadEnqueuer] at
 * download start and rewritten by [org.siloserver.silo.common.downloads.DownloadWorker]
 * on every status transition (downloading → completed / failed). Read by
 * [org.siloserver.silo.repository.DownloadsRepository] on init to seed the
 * in-memory cache before any server refresh, and by the player's offline
 * path to find the local file's contentId without a network round-trip.
 */
@Serializable
data class DownloadSidecar(
    val record: DownloadRecord,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val posterThumbhash: String? = null,
    val year: Int? = null,
    val seriesTitle: String? = null,
    /** Stable parent series/show content id — the rename-safe grouping key for the
     *  tiered Downloads tab (TV/manga). Null for movies; books group by [author]. */
    val seriesContentId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Original server-provided basename for the downloaded file, when
     *  available. Downloads are stored using this name under a fileId-owned
     *  directory so local playback/reading sees the real format. */
    val fileName: String? = null,
    /** Server-provided container / extension hint for the downloaded file. */
    val container: String? = null,
    /** Android MediaStore / public file URI for the downloaded bytes. */
    val localUri: String? = null,
    /** Stable wire string of [org.siloserver.silo.model.download.DownloadMediaType]
     *  — drives Downloads-tab section + renderer choice. Default empty
     *  for back-compat with sidecars written before this field existed;
     *  those decode as [DownloadMediaType.Unknown] but the viewmodel can
     *  reclassify via `seriesTitle != null` heuristic when re-rendering. */
    val mediaType: String = "",
    /** Catalog overview/description, cached so the offline player can show an
     *  "About" section without a server round-trip. */
    val overview: String? = null,
    /** Audiobook author display string, for the offline player header. */
    val author: String? = null,
    /** Audiobook narrator display string, for the offline player header. */
    val narrator: String? = null,
    /** Total media duration (seconds), so the offline progress bar + chapter
     *  ticks render without the server detail. */
    val durationSeconds: Double? = null,
    /** Chapter spans, cached so the offline audiobook player can show the
     *  chapter list + chapter-aware progress. Empty/absent for non-chaptered
     *  media and for sidecars written before this field existed. */
    val chapters: List<VersionChapter>? = null,
    /**
     * How many parts the audiobook this file belongs to has.
     *
     * Captured at download time because it cannot be recovered afterwards. A
     * download is one file, and offline there is no server detail to say
     * whether that file is the whole book or one part of it — which is the
     * difference between "this file's clock is the book's clock" and "it is
     * not". Without it an offline session has to guess, and a guess written
     * into durable, book-scoped data (resume position, bookmarks) is acted on
     * later by sessions that know better.
     *
     * 1 for a single-part book. Null for non-audiobooks and for sidecars
     * written before this existed, which stay ambiguous.
     */
    @SerialName("audiobook_part_count") val audiobookPartCount: Int? = null,
    /** This file's position in the book's part order, so "is this the last
     *  part" survives offline where the other parts are absent. */
    @SerialName("audiobook_part_index") val audiobookPartIndex: Int? = null,
    /**
     * This part's own length as the timeline computed it, which is not always
     * [durationSeconds]. A part with no probed duration takes its length from
     * its chapter edges, while [durationSeconds] falls back to the whole book's
     * total — using that as the part's span would stretch it over later parts.
     */
    @SerialName("audiobook_part_duration_seconds")
    val audiobookPartDurationSeconds: Double? = null,
    /** Where this file starts in whole-book seconds. With it, part-local time
     *  converts offline without the server detail. Null when unknown. */
    @SerialName("audiobook_part_start_offset_seconds")
    val audiobookPartStartOffsetSeconds: Double? = null,
    /** The whole book's duration in seconds, as opposed to [durationSeconds],
     *  which is this file's. Null when unknown. */
    @SerialName("audiobook_total_seconds") val audiobookTotalSeconds: Double? = null,
    /** HTTP validator (strong ETag, else Last-Modified) captured at download start,
     *  used to send `If-Range` when resuming an interrupted transfer so a changed
     *  source file restarts cleanly instead of corrupting. Null/absent = none. */
    val resumeValidator: String? = null,
    /** Wall-clock millis when the sidecar was last written. Diagnostic only;
     *  helps debug stale-file scenarios via `ls -la`. */
    val updatedAtMs: Long,
)
