package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.metadata.MetadataLocale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How confident Find is that a candidate is your book. Nothing is hidden: Maybe is a group, not a filter. */
@Serializable
enum class MatchTier {
    /** A score of 0.8 or more, with a length to compare against your copy. */
    STRONG,

    /** Anything less. */
    MAYBE,
}

/** An audiobook's edition, where a catalogue or your copy says. */
@Serializable
enum class EditionFormat {
    /** The full text, read. */
    UNABRIDGED,

    /** A shortened reading. */
    ABRIDGED,

    /** A dramatisation or full-cast production. */
    DRAMATIZED,
}

/** A candidate's identity: its ref at every source that found it. Review and Apply take it back. */
@Serializable
@SerialName("BookCandidateKey")
data class BookCandidateKey(
    @SerialName("refs") val refs: List<ExternalRef>,
) {
    init {
        require(refs.isNotEmpty()) { "A candidate key needs at least one ref" }
    }
}

/** One source that found a candidate, and the store it found it in when the source has stores. */
@Serializable
@SerialName("FoundIn")
data class FoundIn(
    @SerialName("source") val source: MetadataSource,
    @SerialName("region") val region: String? = null,
)

/** Why a candidate ranks where it does. The server orders them; clients phrase them and show the first three. */
@Serializable
sealed interface MatchReason {
    /** It credits the same narrators as your copy. */
    @Serializable
    @SerialName("MatchReason.SameNarrator")
    data object SameNarrator : MatchReason

    /** It credits different narrators. */
    @Serializable
    @SerialName("MatchReason.DifferentNarrators")
    data object DifferentNarrators : MatchReason

    /** Its length is within 30 seconds of yours. */
    @Serializable
    @SerialName("MatchReason.SameLength")
    data object SameLength : MatchReason

    /** Its length is within [minutes] minutes of yours (up to five). */
    @Serializable
    @SerialName("MatchReason.LengthWithin")
    data class LengthWithin(
        @SerialName("minutes") val minutes: Int,
    ) : MatchReason

    /** Its length differs by [deltaMinutes]: negative is shorter than yours, positive longer. */
    @Serializable
    @SerialName("MatchReason.LengthDiffers")
    data class LengthDiffers(
        @SerialName("deltaMinutes") val deltaMinutes: Int,
    ) : MatchReason

    /** Its length can't be compared with yours, so it can't be a Strong match. */
    @Serializable
    @SerialName("MatchReason.LengthUnknown")
    data object LengthUnknown : MatchReason

    /** It has the same [count] of chapters as your copy. */
    @Serializable
    @SerialName("MatchReason.SameChapterCount")
    data class SameChapterCount(
        @SerialName("count") val count: Int,
    ) : MatchReason

    /** It has [theirs] chapters, not your count. */
    @Serializable
    @SerialName("MatchReason.DifferentChapterCount")
    data class DifferentChapterCount(
        @SerialName("theirs") val theirs: Int,
    ) : MatchReason

    /** It was found in another store, [region], than the one searched. */
    @Serializable
    @SerialName("MatchReason.DifferentStore")
    data class DifferentStore(
        @SerialName("region") val region: MetadataLocale,
    ) : MatchReason

    /** It is a different edition from yours: [format]. */
    @Serializable
    @SerialName("MatchReason.DifferentEdition")
    data class DifferentEdition(
        @SerialName("format") val format: EditionFormat,
    ) : MatchReason
}

/**
 * One book Find found, merged across the sources that agree it is the same edition, and ranked against your
 * copy. [key] is what Review takes back. [isCurrentLink] is labelled, never pinned to the top.
 */
@Serializable
@SerialName("BookCandidate")
data class BookCandidate(
    @SerialName("key") val key: BookCandidateKey,
    @SerialName("title") val title: String,
    @SerialName("subtitle") val subtitle: String?,
    @SerialName("authors") val authors: List<String>,
    @SerialName("narrators") val narrators: List<String>,
    @SerialName("durationMs") val durationMs: Long?,
    @SerialName("year") val year: Int?,
    @SerialName("format") val format: EditionFormat?,
    @SerialName("chapterCount") val chapterCount: Int?,
    @SerialName("coverUrl") val coverUrl: String?,
    @SerialName("foundIn") val foundIn: List<FoundIn>,
    @SerialName("tier") val tier: MatchTier,
    @SerialName("score") val score: Double,
    @SerialName("isBest") val isBest: Boolean,
    @SerialName("isCurrentLink") val isCurrentLink: Boolean,
    @SerialName("reasons") val reasons: List<MatchReason>,
)
