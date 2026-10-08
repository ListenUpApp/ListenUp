package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.metadata.MetadataLocale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A metadata provider as clients show it. [label] is what people read ("Audible", "Hardcover"); [id] is an
 * opaque key clients compare and pass back, never branch on.
 */
@Serializable
@SerialName("MetadataSource")
data class MetadataSource(
    @SerialName("id") val id: String,
    @SerialName("label") val label: String,
)

/** Why a source took no part in a search. */
@Serializable
enum class UnavailableReason {
    /** It needs credentials nobody has given it — Hardcover with no API token and no connected account. */
    NOT_CONFIGURED,

    /** An admin switched it off. */
    DISABLED,
}

/**
 * How one source fared in a Find. A Find where every source failed is still a successful call carrying
 * these, so partial and total failure are one shape and the client chooses the screen.
 */
@Serializable
sealed interface SourceStatus {
    /** The source this status is about. */
    val source: MetadataSource

    /** It answered, with [results] hits (possibly none). */
    @Serializable
    @SerialName("SourceStatus.Answered")
    data class Answered(
        @SerialName("source") override val source: MetadataSource,
        @SerialName("results") val results: Int,
    ) : SourceStatus

    /** It didn't answer within Find's per-source deadline. */
    @Serializable
    @SerialName("SourceStatus.TimedOut")
    data class TimedOut(
        @SerialName("source") override val source: MetadataSource,
    ) : SourceStatus

    /** It asked us to slow down; asking again is worthwhile after [retryAfterSeconds]. */
    @Serializable
    @SerialName("SourceStatus.RateLimited")
    data class RateLimited(
        @SerialName("source") override val source: MetadataSource,
        @SerialName("retryAfterSeconds") val retryAfterSeconds: Long,
    ) : SourceStatus

    /** It failed for any other reason: an outage, an answer we couldn't read. */
    @Serializable
    @SerialName("SourceStatus.Failed")
    data class Failed(
        @SerialName("source") override val source: MetadataSource,
    ) : SourceStatus

    /**
     * A source with stores answered, but its [region] store has nothing for this book. [suggestedRegions] —
     * at most two — are stores worth trying, worked out from what we know, never probed.
     */
    @Serializable
    @SerialName("SourceStatus.NotFoundInStore")
    data class NotFoundInStore(
        @SerialName("source") override val source: MetadataSource,
        @SerialName("region") val region: MetadataLocale,
        @SerialName("suggestedRegions") val suggestedRegions: List<MetadataLocale>,
    ) : SourceStatus

    /** It took no part, for [reason]. */
    @Serializable
    @SerialName("SourceStatus.Unavailable")
    data class Unavailable(
        @SerialName("source") override val source: MetadataSource,
        @SerialName("reason") val reason: UnavailableReason,
    ) : SourceStatus
}

/** Where a Find's store came from. */
@Serializable
enum class RegionOrigin {
    /** The person picked a store for this search only. */
    SEARCH_OVERRIDE,

    /** The library's store, set by an admin. */
    LIBRARY,

    /** Neither: the server's default store. */
    SERVER_DEFAULT,
}

/** The store a Find searched at [source]: [region], where it came from, and the stores the person can pick. */
@Serializable
@SerialName("RegionContext")
data class RegionContext(
    @SerialName("source") val source: MetadataSource,
    @SerialName("region") val region: MetadataLocale,
    @SerialName("origin") val origin: RegionOrigin,
    @SerialName("choices") val choices: List<MetadataLocale>,
)
