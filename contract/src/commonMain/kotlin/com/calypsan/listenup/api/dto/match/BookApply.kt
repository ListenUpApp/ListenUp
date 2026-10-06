package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything one Apply writes, in one transaction. [chapterOrdinals] empty leaves chapter names alone; a field
 * absent from [fields] is kept.
 */
@Serializable
@SerialName("BookMatchApply")
data class BookMatchApply(
    @SerialName("candidate") val candidate: BookCandidateKey,
    @SerialName("region") val region: MetadataLocale?,
    @SerialName("basedOnRevision") val basedOnRevision: Long,
    @SerialName("fields") val fields: List<FieldDecision>,
    @SerialName("cover") val cover: ImageChoice,
    @SerialName("genres") val genres: LabelSetChange,
    @SerialName("moods") val moods: LabelSetChange,
    @SerialName("chapterOrdinals") val chapterOrdinals: List<Int>,
)

/** One thing an Apply changed, and where it came from. Clients compose the receipt's sentence from these. */
@Serializable
sealed interface AppliedChange {
    /** [field] was written from [source]. */
    @Serializable
    @SerialName("AppliedChange.Field")
    data class Field(
        @SerialName("field") val field: BookField,
        @SerialName("source") val source: MetadataSource,
    ) : AppliedChange

    /** The cover was replaced with [source]'s. */
    @Serializable
    @SerialName("AppliedChange.Cover")
    data class Cover(
        @SerialName("source") val source: MetadataSource,
    ) : AppliedChange

    /** Genres [added] and [removed]. */
    @Serializable
    @SerialName("AppliedChange.Genres")
    data class Genres(
        @SerialName("added") val added: List<String>,
        @SerialName("removed") val removed: List<String>,
    ) : AppliedChange

    /** Moods [added] and [removed]. */
    @Serializable
    @SerialName("AppliedChange.Moods")
    data class Moods(
        @SerialName("added") val added: List<String>,
        @SerialName("removed") val removed: List<String>,
    ) : AppliedChange

    /** [count] chapter names came from [source]. */
    @Serializable
    @SerialName("AppliedChange.ChapterNames")
    data class ChapterNames(
        @SerialName("count") val count: Int,
        @SerialName("source") val source: MetadataSource,
    ) : AppliedChange

    /** A person's photo came from [source]. */
    @Serializable
    @SerialName("AppliedChange.Photo")
    data class Photo(
        @SerialName("source") val source: MetadataSource,
    ) : AppliedChange

    /** A person's biography came from [source]. */
    @Serializable
    @SerialName("AppliedChange.Biography")
    data class Biography(
        @SerialName("source") val source: MetadataSource,
    ) : AppliedChange
}

/** What an Apply did. [receiptId] is the Undo token while [undoable]. */
@Serializable
@SerialName("MatchReceipt")
data class MatchReceipt(
    @SerialName("receiptId") val receiptId: String,
    @SerialName("appliedAt") val appliedAt: Long,
    @SerialName("changes") val changes: List<AppliedChange>,
    @SerialName("undoable") val undoable: Boolean,
)

/** An Undo that went through: [restored] is what it put back. */
@Serializable
@SerialName("UndoResult")
data class UndoResult(
    @SerialName("receiptId") val receiptId: String,
    @SerialName("restored") val restored: List<AppliedChange>,
)

/**
 * A book's live match, on its sync payload. It can be undone while [revision] still equals the book's revision;
 * any later change to the book retires it.
 */
@Serializable
@SerialName("LastMatch")
data class LastMatch(
    @SerialName("receiptId") val receiptId: String,
    @SerialName("appliedAt") val appliedAt: Long,
    @SerialName("appliedBy") val appliedBy: String,
    @SerialName("revision") val revision: Long,
    @SerialName("changes") val changes: List<AppliedChange>,
)
