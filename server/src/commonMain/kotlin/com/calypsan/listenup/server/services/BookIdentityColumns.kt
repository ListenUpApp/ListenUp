package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.sync.BookSyncPayload

/**
 * The two identity invariants every book write passes through, as pure functions.
 *
 *  - **The `asin` column is the `audible` ref.** Older clients and the scanner write the column; matching
 *    writes refs. Reconciling the ref to the column on every write keeps them one value, whoever wrote it.
 *  - **A stored release date's year is `publish_year`.** The date is precision behind the displayed year
 *    (decision 11), so a write that changes the year without a date — a hand edit, an older client, a
 *    scan — clears the date rather than leaving two answers.
 */
internal object BookIdentityColumns {
    private val isoDate = Regex("""(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")

    /** [raw] as a full ISO date (`yyyy-mm-dd`), trimmed, or null when it is anything else. */
    fun fullDateOrNull(raw: String?): String? = raw?.trim()?.takeIf { isoDate.matches(it) }

    /** [releaseDate] when it is a full ISO date in [publishYear], else null. */
    fun reconcileReleaseDate(
        publishYear: Int?,
        releaseDate: String?,
    ): String? {
        val date = fullDateOrNull(releaseDate) ?: return null
        val year = isoDate.matchEntire(date)?.groupValues?.get(1)?.toInt()
        return date.takeIf { year == publishYear }
    }

    /**
     * [refs] with the `audible` entry rebuilt from [asin] (kept as-is, store included, when it already
     * names that ASIN), one ref per provider (first wins), sorted by provider so a write and its re-read
     * compare equal.
     */
    fun reconcileRefs(
        asin: String?,
        refs: List<ExternalRef>,
    ): List<ExternalRef> {
        val audibleId = asin?.trim()?.takeIf { it.isNotEmpty() }
        val audible =
            audibleId?.let { id ->
                refs.firstOrNull { it.provider == ExternalRef.AUDIBLE && it.id == id } ?: ExternalRef(ExternalRef.AUDIBLE, id)
            }
        val others = refs.filter { it.provider != ExternalRef.AUDIBLE }.distinctBy { it.provider }
        return (listOfNotNull(audible) + others).sortedBy { it.provider }
    }
}

/** This payload with both identity invariants applied — what [BookRepository] actually writes. */
internal fun BookSyncPayload.withReconciledIdentity(): BookSyncPayload =
    copy(
        externalRefs = BookIdentityColumns.reconcileRefs(asin, externalRefs),
        releaseDate = BookIdentityColumns.reconcileReleaseDate(publishYear, releaseDate),
    )
