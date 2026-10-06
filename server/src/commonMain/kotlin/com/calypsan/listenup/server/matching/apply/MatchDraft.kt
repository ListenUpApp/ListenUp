package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.server.matching.review.OptionWrite
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.SeriesMeta

/** What a match does to one label set (genres or moods). */
internal sealed interface LabelPlan {
    /** Leave the labels alone. */
    data object Unchanged : LabelPlan

    /** Add [add] and remove [remove] (names of yours) — Match details. */
    data class AddRemove(
        val add: List<String>,
        val remove: List<String>,
    ) : LabelPlan

    /** Make the labels exactly [labels] — the legacy apply, unchanged for older clients. */
    data class ReplaceAll(
        val labels: List<String>,
    ) : LabelPlan
}

/** The cover a match writes: [url] from [provider]. A [required] cover that can't be fetched fails the apply. */
internal data class DraftCover(
    val url: String,
    val provider: MetadataProviderId,
    val required: Boolean,
)

/**
 * What one match will write, by name — before any catalogue row is resolved or created (spec, *Apply*, step 1).
 * Pure output of [MatchPlanner] (Match details) and [LegacyMatchPlanner] (the legacy apply); [MatchPreparer]
 * resolves it into a [MatchWritePlan].
 *
 * [texts] maps a text field to its new value (null clears it — the legacy apply can). [authors] and [narrators]
 * replace that role's whole credit list. [refs] replace those providers' refs; [asin] is the Audible key the
 * book's column mirrors. [provenance] names the provider each written field is stamped `ENRICHMENT` with.
 * [chapterTitles] are new names by ordinal in start-time order. [changes] is the receipt, less what only
 * preparation can confirm (a best-effort cover that couldn't be fetched is dropped from it).
 */
internal data class MatchDraft(
    val texts: Map<BookField, String?> = emptyMap(),
    val year: OptionWrite.Year? = null,
    val authors: List<String>? = null,
    val narrators: List<String>? = null,
    val series: List<SeriesMeta>? = null,
    val refs: List<ExternalRef> = emptyList(),
    val asin: String? = null,
    val provenance: Map<BookField, MetadataProviderId> = emptyMap(),
    val cover: DraftCover? = null,
    val genres: LabelPlan = LabelPlan.Unchanged,
    val ladders: List<List<String>> = emptyList(),
    val moods: LabelPlan = LabelPlan.Unchanged,
    val chapterTitles: Map<Int, String> = emptyMap(),
    val changes: List<AppliedChange> = emptyList(),
)
