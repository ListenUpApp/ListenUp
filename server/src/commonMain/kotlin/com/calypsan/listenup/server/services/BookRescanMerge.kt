package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.SidecarCuration
import com.calypsan.listenup.api.dto.scanner.SidecarCurationChapter
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource

/**
 * The outcome of merging a scan write against the stored book's per-field provenance: the
 * [payload] to persist (higher-tier scalar fields restored to their stored values,
 * [BookSyncPayload.fieldProvenance] carrying the per-field max-tier union) plus the
 * contributor/series/genre preserve flags that the aggregate write honours via [BookWriteExtras].
 */
internal data class ProvenanceMerge(
    val payload: BookSyncPayload,
    val preserveContributors: Boolean,
    val preserveSeries: Boolean,
    val preserveGenres: Boolean,
)

/**
 * Merges an [incoming] SCAN write against the [existing] stored aggregate so a user's hand-edits
 * and a provider's enrichment survive a rescan — the field-level generalization of the
 * sticky-chapters / sticky-uploaded-cover guards.
 *
 * **THE ONE WRITE RULE.** A write may replace a field's value iff its tier `>=` the stored
 * provenance's tier (scan `0` < enrichment `1` < user `2`). This is the scan path, so every
 * incoming field's authority is [com.calypsan.listenup.api.metadata.FieldSourceKind.FOLDER]'s tier `0`.
 * A field is therefore *preserved* (stored wins) exactly when its stored provenance tier is `> 0` —
 * i.e. it was enriched or hand-edited. For those fields the scalar value is restored onto the returned
 * [ProvenanceMerge.payload]; the contributor/series/genre collections are flagged so the aggregate
 * write skips their replace.
 *
 * The persisted [BookSyncPayload.fieldProvenance] is the per-field max-tier union: enrichment/user
 * entries stay sticky, scan-won fields take the incoming scan provenance, and a field the rescan no
 * longer derives drops out (its value cleared, its provenance with it). Computing the merge BEFORE
 * the [matchesStoredContent] skip check is load-bearing: a rescan that only "disagrees" on a
 * protected scalar then matches stored and naturally skips, with no revision bump or firehose event.
 */
internal fun mergeByProvenance(
    incoming: BookSyncPayload,
    existing: BookSyncPayload,
): ProvenanceMerge {
    // A scan write's authority is tier 0 for every field, so a stored field is protected iff its
    // recorded tier is above scan (enrichment or user). Never sticky within the scan tier itself.
    fun isProtected(field: BookField): Boolean = (existing.fieldProvenance[field]?.tier ?: 0) > 0

    fun <T> kept(
        field: BookField,
        stored: T,
        scanned: T,
    ): T = if (isProtected(field)) stored else scanned

    // Per-field max-tier union: start from the scan-derived (tier-0) entries this write produced,
    // then overlay every stored entry that out-ranks a scan (enrichment/user), keeping it sticky.
    val mergedProvenance =
        buildMap {
            putAll(incoming.fieldProvenance)
            existing.fieldProvenance.forEach { (field, prov) -> if (prov.tier > 0) put(field, prov) }
        }

    val merged =
        incoming.copy(
            title = kept(BookField.TITLE, existing.title, incoming.title),
            sortTitle = kept(BookField.SORT_TITLE, existing.sortTitle, incoming.sortTitle),
            subtitle = kept(BookField.SUBTITLE, existing.subtitle, incoming.subtitle),
            description = kept(BookField.DESCRIPTION, existing.description, incoming.description),
            publisher = kept(BookField.PUBLISHER, existing.publisher, incoming.publisher),
            language = kept(BookField.LANGUAGE, existing.language, incoming.language),
            publishYear = kept(BookField.PUBLISH_YEAR, existing.publishYear, incoming.publishYear),
            isbn = kept(BookField.ISBN, existing.isbn, incoming.isbn),
            asin = kept(BookField.ASIN, existing.asin, incoming.asin),
            abridged = kept(BookField.ABRIDGED, existing.abridged, incoming.abridged),
            explicit = kept(BookField.EXPLICIT, existing.explicit, incoming.explicit),
            // The scanner never derives a release date or a catalogue link: both carry forward, and
            // writePayload then reconciles them against the year and the ASIN this scan settled on.
            releaseDate = existing.releaseDate,
            externalRefs = existing.externalRefs,
            fieldProvenance = mergedProvenance,
        )
    return ProvenanceMerge(
        payload = merged,
        // Contributors are a single whole-list replace covering both roles, so either author or
        // narrator provenance out-ranking a scan pins the list (behaviour-identical to the old
        // coarse CONTRIBUTORS flag).
        preserveContributors = isProtected(BookField.AUTHORS) || isProtected(BookField.NARRATORS),
        preserveSeries = isProtected(BookField.SERIES),
        preserveGenres = isProtected(BookField.GENRES),
    )
}

/**
 * Applies [curation] — re-ingested from an external `listenup.json` sidecar — onto a
 * scan payload AFTER [mergeByProvenance] ran.
 *
 * Provenance merges by **max tier**, the same one write rule [mergeByProvenance] enforces:
 * a sidecar entry replaces the payload's entry for that field iff it out-ranks it. That is
 * what makes the sidecar restore correct in both directions — after a DB wipe the payload
 * carries only tier-0 scan entries, so every recorded USER/ENRICHMENT entry wins and the
 * user's curation comes back at the tier it was recorded at; against a live DB, a stale
 * sidecar can never demote a higher-tier value the merge already restored.
 *
 * [SidecarCuration.userChapters], when present, replace the scan-derived chapter set with
 * `chapterSource = USER` (writePayload's sticky-chapters guard admits USER-sourced incoming
 * chapters). Running after the merge is load-bearing: the sidecar's fields must never count
 * as *incoming re-edits*, or a scan could clobber a stored protected value with a
 * scanner-derived one.
 */
internal fun BookSyncPayload.withSidecarCuration(curation: SidecarCuration?): BookSyncPayload {
    if (curation == null) return this
    val merged =
        buildMap {
            putAll(fieldProvenance)
            curation.fieldProvenance.forEach { (field, prov) ->
                val stored = this[field]
                if (stored == null || prov.tier > stored.tier) put(field, prov)
            }
        }
    val withProvenance = copy(fieldProvenance = merged)
    val userChapters = curation.userChapters ?: return withProvenance
    return withProvenance.copy(
        chapters = userChapters.toChapterPayloads(totalDuration),
        chapterSource = ChapterSource.USER,
        // Restored only on the insert path (`books.insert` writes these columns; the rescan's
        // `updateContent` never does), which is exactly the shape wanted: after a wipe the row
        // is new and the sidecar's vocabulary comes back with it, while against a live database
        // a stale sidecar cannot overwrite a name the user has since changed.
        bookTierLabel = curation.bookTierLabel,
        partTierLabel = curation.partTierLabel,
    )
}

/**
 * True when [this] freshly-scanned payload matches the [stored] aggregate in every content field.
 *
 * Normalizes the scanned payload's placeholder server-assigned fields (revision/updatedAt/createdAt
 * = 0, scannedAt = now), its `null` cover, its empty `genres`, and its unset tier labels to the
 * stored values before comparing, so the result reflects only real content changes. Audio-file and
 * chapter rows drop their `id` (server-generated UUID at rest, `""` from the mapper) before comparing.
 *
 * `fieldProvenance` is normalized away too: it is derived metadata *about* the values, not content.
 * A scan re-authors its tier-0 entries with a fresh `at` on every pass, so leaving it in the
 * equality would make every idempotent rescan look "changed" and bump the revision. Ignoring it
 * means an idempotent rescan skips (and the stored provenance stays stable, untouched by the skip).
 */
internal fun BookSyncPayload.matchesStoredContent(stored: BookSyncPayload): Boolean {
    val normalized =
        copy(
            revision = stored.revision,
            updatedAt = stored.updatedAt,
            createdAt = stored.createdAt,
            scannedAt = stored.scannedAt,
            deletedAt = stored.deletedAt,
            cover = stored.cover,
            genres = stored.genres,
            fieldProvenance = stored.fieldProvenance,
            // Tier names are normalized away for the same reason the cover is: no write on
            // this path can change them (`updateContent` does not name those columns), so a
            // difference here is never a real content change. Comparing them would make every
            // rescan of a book whose tiers the user named look "changed" and churn its
            // revision on every scan, forever.
            bookTierLabel = stored.bookTierLabel,
            partTierLabel = stored.partTierLabel,
            // The live match receipt is read, never written, by this path: a rescan that changes
            // nothing must not look "changed" and expire the book's Undo.
            lastMatch = stored.lastMatch,
            audioFiles = audioFiles.map { it.copy(id = "") },
            chapters = chapters.map { it.copy(id = "") },
        )
    val storedNormalized =
        stored.copy(
            audioFiles = stored.audioFiles.map { it.copy(id = "") },
            chapters = stored.chapters.map { it.copy(id = "") },
        )
    return normalized == storedNormalized
}

/**
 * Converts sidecar-curated chapters (title + startMs only) to persistable chapter rows:
 * each chapter's duration runs to the next chapter's start, the last to [totalDurationMs].
 */
private fun List<SidecarCurationChapter>.toChapterPayloads(totalDurationMs: Long): List<BookChapterPayload> {
    val sorted = sortedBy { it.startMs }
    return sorted.mapIndexed { index, chapter ->
        val end = sorted.getOrNull(index + 1)?.startMs ?: totalDurationMs
        BookChapterPayload(
            id = "",
            title = chapter.title,
            duration = (end - chapter.startMs).coerceAtLeast(0L),
            startTime = chapter.startMs,
            partTitle = chapter.partTitle,
            bookTitle = chapter.bookTitle,
        )
    }
}
