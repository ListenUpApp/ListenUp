package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a reviewed field compares with yours. The server decides, so every client agrees. */
@Serializable
enum class FieldState {
    /** It differs from yours, and you didn't edit yours by hand. Ticked. */
    CHANGES,

    /** You have nothing there. Ticked. */
    FILLS_GAP,

    /** The best option already matches yours. Collapsed, not ticked. */
    SAME,

    /** It differs from a value you edited by hand. Unticked and flagged. */
    USER_EDITED,
}

/** One field's value, as Review shows it. Release dates are compared and shown as a [Year]. */
@Serializable
sealed interface FieldValue {
    /** Plain text: a title, a publisher, a description, a language. */
    @Serializable
    @SerialName("FieldValue.Text")
    data class Text(
        @SerialName("text") val text: String,
    ) : FieldValue

    /** People credited in one role, in credit order. */
    @Serializable
    @SerialName("FieldValue.People")
    data class People(
        @SerialName("names") val names: List<String>,
    ) : FieldValue

    /** Series placements. */
    @Serializable
    @SerialName("FieldValue.SeriesEntries")
    data class SeriesEntries(
        @SerialName("entries") val entries: List<MatchSeriesEntry>,
    ) : FieldValue

    /** A release year. */
    @Serializable
    @SerialName("FieldValue.Year")
    data class Year(
        @SerialName("year") val year: Int,
    ) : FieldValue
}

/** A book's place in one series, as Review shows it: the series [name] and the [sequence] as written ("1.5"). */
@Serializable
@SerialName("MatchSeriesEntry")
data class MatchSeriesEntry(
    @SerialName("name") val name: String,
    @SerialName("sequence") val sequence: String?,
)

/**
 * One proposed value for a field, and every source that offered it (identical values from two sources are one
 * option). [optionId] is derived from the value, so the same option has the same id on every Review.
 */
@Serializable
@SerialName("FieldOption")
data class FieldOption(
    @SerialName("optionId") val optionId: String,
    @SerialName("value") val value: FieldValue,
    @SerialName("sources") val sources: List<MetadataSource>,
)

/**
 * Who edited a field by hand, and when. Any of them may be unknown: an edit from a sidecar or from before edits
 * were tracked is still an edit ("Edited by hand"). [byName] is resolved by the server.
 */
@Serializable
@SerialName("HandEdit")
data class HandEdit(
    @SerialName("byUserId") val byUserId: String?,
    @SerialName("byName") val byName: String?,
    @SerialName("at") val at: Long?,
)

/** One reviewable field: yours, every source's option, what Apply does by default, and why. */
@Serializable
@SerialName("FieldReview")
data class FieldReview(
    @SerialName("field") val field: BookField,
    @SerialName("current") val current: FieldValue?,
    @SerialName("options") val options: List<FieldOption>,
    @SerialName("defaultChoice") val defaultChoice: FieldChoice,
    @SerialName("state") val state: FieldState,
    @SerialName("handEdit") val handEdit: HandEdit?,
)

/** Your current cover: its content [hash] (clients build its URL as they always do) and whether a person set it. */
@Serializable
@SerialName("CurrentCover")
data class CurrentCover(
    @SerialName("hash") val hash: String?,
    @SerialName("setByHand") val setByHand: Boolean,
)

/** One cover a source offers, with its probed pixel size (0 when the probe couldn't tell). */
@Serializable
@SerialName("CoverCandidate")
data class CoverCandidate(
    @SerialName("optionId") val optionId: String,
    @SerialName("source") val source: MetadataSource,
    @SerialName("url") val url: String,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
)

/** The cover decision: yours (null when the book has none), every candidate, and the default (decision 6). */
@Serializable
@SerialName("CoverReview")
data class CoverReview(
    @SerialName("current") val current: CurrentCover?,
    @SerialName("options") val options: List<CoverCandidate>,
    @SerialName("defaultChoice") val defaultChoice: ImageChoice,
)

/** A label a source suggests, and every source that suggested it. */
@Serializable
@SerialName("LabelSuggestion")
data class LabelSuggestion(
    @SerialName("label") val label: String,
    @SerialName("sources") val sources: List<MetadataSource>,
)

/** Genres or moods: yours, kept unless you remove one, and suggestions you don't have yet (all start selected). */
@Serializable
@SerialName("LabelSetReview")
data class LabelSetReview(
    @SerialName("yours") val yours: List<String>,
    @SerialName("suggested") val suggested: List<LabelSuggestion>,
)

/** One chapter whose name would change: its [ordinal] in your chapter order, your name, and the source's. */
@Serializable
@SerialName("ChapterNameChange")
data class ChapterNameChange(
    @SerialName("ordinal") val ordinal: Int,
    @SerialName("yours") val yours: String,
    @SerialName("theirs") val theirs: String,
)

/** Whether chapter names can be applied from this match. */
@Serializable
sealed interface ChapterNamesReview {
    /** [source] names [rows] differently; [unchangedCount] more already match. */
    @Serializable
    @SerialName("ChapterNamesReview.Available")
    data class Available(
        @SerialName("source") val source: MetadataSource,
        @SerialName("rows") val rows: List<ChapterNameChange>,
        @SerialName("unchangedCount") val unchangedCount: Int,
    ) : ChapterNamesReview

    /** [source] has [theirs] chapters and you have [yours], so names can't be lined up. */
    @Serializable
    @SerialName("ChapterNamesReview.CountMismatch")
    data class CountMismatch(
        @SerialName("source") val source: MetadataSource,
        @SerialName("yours") val yours: Int,
        @SerialName("theirs") val theirs: Int,
    ) : ChapterNamesReview

    /** No source has chapter names for this match. */
    @Serializable
    @SerialName("ChapterNamesReview.Unavailable")
    data object Unavailable : ChapterNamesReview
}

/**
 * Review for one Find candidate: every field Yours → Proposed with its sources, the cover, genres, moods and
 * chapter names. Apply sends back [basedOnRevision]; if the book or an option changed since, Apply refuses with
 * `MetadataError.ReviewOutdated` and writes nothing.
 */
@Serializable
@SerialName("BookMatchReview")
data class BookMatchReview(
    @SerialName("candidate") val candidate: BookCandidateKey,
    @SerialName("region") val region: MetadataLocale?,
    @SerialName("basedOnRevision") val basedOnRevision: Long,
    @SerialName("fields") val fields: List<FieldReview>,
    @SerialName("cover") val cover: CoverReview,
    @SerialName("genres") val genres: LabelSetReview,
    @SerialName("moods") val moods: LabelSetReview,
    @SerialName("chapterNames") val chapterNames: ChapterNamesReview,
)
