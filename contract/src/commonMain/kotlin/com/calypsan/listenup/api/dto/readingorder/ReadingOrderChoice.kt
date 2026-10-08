package com.calypsan.listenup.api.dto.readingorder

import com.calypsan.listenup.core.ReadingOrderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which order a user follows on one series (#962): one of the two computed built-ins, or a user-made order
 * on that series or one of its ancestors. This is the RPC parameter shape — it only travels client →
 * server, so a sealed type is safe here; the synced row uses the flat [ReadingOrderChoiceKind] instead.
 */
@Serializable
sealed interface ReadingOrderChoice {
    /** The kind this choice is stored and synced as. */
    val kind: ReadingOrderChoiceKind

    /** The user-made order's id, or null for a built-in. */
    fun readingOrderIdOrNull(): ReadingOrderId? = (this as? UserMade)?.readingOrderId

    /** The hierarchy's default order: each sub-series in turn, then the series' own books. */
    @Serializable
    @SerialName("ReadingOrderChoice.SeriesOrder")
    data object SeriesOrder : ReadingOrderChoice {
        override val kind: ReadingOrderChoiceKind get() = ReadingOrderChoiceKind.SERIES
    }

    /** The subtree by first release date; undated books last, in Series order. */
    @Serializable
    @SerialName("ReadingOrderChoice.PublicationOrder")
    data object PublicationOrder : ReadingOrderChoice {
        override val kind: ReadingOrderChoiceKind get() = ReadingOrderChoiceKind.PUBLICATION
    }

    /** A user-made order, identified by [readingOrderId]. */
    @Serializable
    @SerialName("ReadingOrderChoice.UserMade")
    data class UserMade(
        @SerialName("readingOrderId") val readingOrderId: ReadingOrderId,
    ) : ReadingOrderChoice {
        override val kind: ReadingOrderChoiceKind get() = ReadingOrderChoiceKind.ORDER
    }

    /** Construction from the flat synced shape. */
    companion object {
        /** Rebuilds a choice from its synced [kind] and id; an ORDER with no id degrades to [SeriesOrder]. */
        fun from(
            kind: ReadingOrderChoiceKind,
            readingOrderId: ReadingOrderId?,
        ): ReadingOrderChoice =
            when (kind) {
                ReadingOrderChoiceKind.SERIES -> SeriesOrder
                ReadingOrderChoiceKind.PUBLICATION -> PublicationOrder
                ReadingOrderChoiceKind.ORDER -> readingOrderId?.let(::UserMade) ?: SeriesOrder
            }
    }
}

/**
 * The flat, synced form of a [ReadingOrderChoice]. Every property of this type must declare a default
 * ([SERIES]): `contractJson`'s `coerceInputValues` then reads a kind a newer server adds as Series order
 * instead of freezing the domain on an older client.
 */
@Serializable
enum class ReadingOrderChoiceKind {
    /** [ReadingOrderChoice.SeriesOrder]. */
    SERIES,

    /** [ReadingOrderChoice.PublicationOrder]. */
    PUBLICATION,

    /** [ReadingOrderChoice.UserMade]. */
    ORDER,
}
