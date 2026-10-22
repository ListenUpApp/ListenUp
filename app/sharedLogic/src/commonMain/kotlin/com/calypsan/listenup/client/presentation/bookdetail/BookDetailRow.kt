package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.mostSpecificGenre

/** One row of Book Detail's Details section (spec §3.2.1 D31). Values only: the words and units are each platform's. */
sealed interface BookDetailRow {
    /** The publisher's name. */
    data class Publisher(
        val name: String,
    ) : BookDetailRow

    /** The year it was published. */
    data class Released(
        val year: Int,
    ) : BookDetailRow

    /** "Epic fantasy · Unabridged": the most specific genre and the edition. */
    data class GenreLine(
        val genreName: String,
        val abridged: Boolean,
    ) : BookDetailRow

    /** The language, as a person names it ("English"). */
    data class Language(
        val displayName: String,
    ) : BookDetailRow

    /** "45h 30m · 75 chapters". [chapterCount] is 0 when there are none. */
    data class Length(
        val durationMs: Long,
        val chapterCount: Int,
    ) : BookDetailRow

    /** When it joined the library, in epoch milliseconds. */
    data class Added(
        val epochMs: Long,
    ) : BookDetailRow

    /** The ISBN, trimmed. */
    data class Isbn(
        val isbn: String,
    ) : BookDetailRow

    /** The Audible ASIN, trimmed. */
    data class Asin(
        val asin: String,
    ) : BookDetailRow

    /** "All credits (6) ›": everyone credited, each once. Opens the credits sheet or dialog. */
    data class AllCredits(
        val count: Int,
    ) : BookDetailRow

    /**
     * "Files & audio (AAC · 64 kbps) ›". Opens the Files & audio sheet (Android, iOS) or the Files view (web).
     *
     * @property format The audio format the sheet names too ([audioFormatDisplay]'s format: "AAC", "Dolby Atmos").
     * @property bitrate "64 kbps"; null when unknown, never "0 kbps".
     */
    data class FilesAndAudio(
        val format: String?,
        val bitrate: String?,
    ) : BookDetailRow

    /**
     * "Maps & illustrations (PDF · 18 MB) ›": the book's documents.
     *
     * @property onlyDocumentId The document to open at once when there is exactly one; null opens the list.
     * @property format The documents' shared format, upper-cased; null when they differ.
     */
    data class MapsAndIllustrations(
        val count: Int,
        val onlyDocumentId: String?,
        val format: String?,
        val totalBytes: Long,
    ) : BookDetailRow
}

/**
 * The Details rows for [ready] and its [documents], in the spec's order: Publisher, Released, Genre, Language, Length,
 * Added, ISBN, ASIN, then All credits, Files & audio and Maps & illustrations. A row whose datum is absent is left out,
 * never drawn as "Unknown". Pure.
 */
fun bookDetailRows(
    ready: BookDetailUiState.Ready,
    documents: List<BookDocument>,
): List<BookDetailRow> {
    val book = ready.book
    val credits = book.allContributors.ifEmpty { book.authors + book.narrators }.distinctBy { it.id }
    return buildList {
        book.publisher.trimmedOrNull()?.let { add(BookDetailRow.Publisher(it)) }
        ready.year?.takeIf { it > 0 }?.let { add(BookDetailRow.Released(it)) }
        book.mostSpecificGenre()?.let { add(BookDetailRow.GenreLine(it.name, book.abridged)) }
        book.language.trimmedOrNull()?.let { add(BookDetailRow.Language(languageDisplayName(it))) }
        if (book.duration > 0) add(BookDetailRow.Length(book.duration, ready.chapters.size))
        ready.addedAt?.let { add(BookDetailRow.Added(it)) }
        book.isbn.trimmedOrNull()?.let { add(BookDetailRow.Isbn(it)) }
        book.asin.trimmedOrNull()?.let { add(BookDetailRow.Asin(it)) }
        if (credits.isNotEmpty()) add(BookDetailRow.AllCredits(credits.size))
        if (book.audioFiles.isNotEmpty()) {
            val audio = audioFormatDisplay(book.audioFiles)
            add(BookDetailRow.FilesAndAudio(format = audio.format, bitrate = audio.bitrate))
        }
        if (documents.isNotEmpty()) {
            add(
                BookDetailRow.MapsAndIllustrations(
                    count = documents.size,
                    onlyDocumentId = documents.singleOrNull()?.id,
                    format = documents.map { it.format.uppercase() }.distinct().singleOrNull(),
                    totalBytes = documents.sumOf { it.size },
                ),
            )
        }
    }
}

private fun String?.trimmedOrNull(): String? = this?.run { trim().takeIf { it.isNotEmpty() } }
