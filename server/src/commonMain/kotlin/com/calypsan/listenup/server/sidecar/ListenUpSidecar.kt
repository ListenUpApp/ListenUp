package com.calypsan.listenup.server.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The `listenup.json` disk format (Foundation Trio Phase 2, spec §5) — a versioned,
 * self-contained snapshot of a book's curated metadata that ListenUp writes beside the
 * book's audio files. Written by [SidecarWriter] whenever curation changes; read back at
 * the highest [com.calypsan.listenup.server.scanner.metadata.MetadataPrecedenceSource]
 * slot by [com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader] so a
 * database wipe (or a friend copying the folder) restores the user's curation on rescan.
 *
 * Field names ARE the disk format — chosen once, never casually renamed; a rename breaks
 * every `listenup.json` already written into someone's library. Unknown fields are
 * ignored on read (forward compat: an older server reading a file a newer server wrote).
 */
@Serializable
data class ListenUpSidecar(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("identity") val identity: SidecarIdentity,
    @SerialName("metadata") val metadata: SidecarCuratedMetadata,
    /** [com.calypsan.listenup.api.sync.UserEditedField] names, as an opaque string list on disk. */
    @SerialName("userEditedFields") val userEditedFields: List<String> = emptyList(),
    /** Only present when the book's chapter source is USER — see [SidecarChapters]. */
    @SerialName("chapters") val chapters: SidecarChapters? = null,
    /** Opaque stubs reserved for the Story World reading-orders artifact (#962). */
    @SerialName("readingOrders") val readingOrders: List<JsonObject> = emptyList(),
)

/**
 * Identity signals a re-import uses to match this sidecar back to a book on disk, per the
 * fallback chain in the Integration Foundations spec §7.4: ASIN when present, else the
 * edition-tolerant [chapterFingerprint], else [titleAuthor] fuzzy match.
 */
@Serializable
data class SidecarIdentity(
    @SerialName("asin") val asin: String? = null,
    /**
     * Canonical v1 chapter-snapshot fingerprint: `sha256Hex(chapters.joinToString("|") {
     * "${it.title.trim().lowercase()}:${it.durationMs / 5000}" })` — title text (trimmed,
     * lowercased) plus each chapter's duration bucketed to 5-second resolution, so minor
     * re-encodes that shift chapter boundaries by a few hundred milliseconds still match.
     * This formula is the identity contract other tracks/tools inherit — changing it
     * invalidates every fingerprint already written into the wild.
     */
    @SerialName("chapterFingerprint") val chapterFingerprint: String? = null,
    /** `"<title> / <primary author>"`, the last-resort fuzzy-match key. */
    @SerialName("titleAuthor") val titleAuthor: String? = null,
)

/**
 * The user-curated metadata fields — the resolved values, not provenance (provenance lives
 * in [ListenUpSidecar.userEditedFields]).
 */
@Serializable
data class SidecarCuratedMetadata(
    @SerialName("title") val title: String? = null,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("contributors") val contributors: List<SidecarContributor> = emptyList(),
    @SerialName("series") val series: List<SidecarSeriesEntry> = emptyList(),
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("tags") val tags: List<String> = emptyList(),
)

/** One contributor credit — mirrors [com.calypsan.listenup.api.sync.BookContributorPayload]'s name+role. */
@Serializable
data class SidecarContributor(
    @SerialName("name") val name: String,
    @SerialName("role") val role: String,
)

/**
 * One series membership. [sequence] is a free-form string (e.g. `"1.5"`), mirroring
 * [com.calypsan.listenup.api.sync.BookSeriesPayload.sequence].
 */
@Serializable
data class SidecarSeriesEntry(
    @SerialName("name") val name: String,
    @SerialName("sequence") val sequence: String? = null,
)

/**
 * The book's hand-edited chapter set. Present only when the book's
 * [com.calypsan.listenup.api.sync.ChapterSource] is `USER` — scanner-derived chapters are
 * re-derived from the files on every scan and never written here.
 */
@Serializable
data class SidecarChapters(
    /** Always `"USER"` today — a literal string (not the enum) so the disk format never depends on enum ordinals. */
    @SerialName("source") val source: String,
    @SerialName("entries") val entries: List<SidecarChapter>,
)

/** One user-edited chapter: a title and its start offset from the start of the book. */
@Serializable
data class SidecarChapter(
    @SerialName("title") val title: String,
    @SerialName("startMs") val startMs: Long,
)
