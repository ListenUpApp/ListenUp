package com.calypsan.listenup.client.domain.model

/**
 * Complete data needed for the book edit screen.
 *
 * Returned by [LoadBookForEditUseCase] and used as the original state
 * for change detection in [UpdateBookUseCase].
 */
data class BookEditData(
    val bookId: String,
    val metadata: BookMetadata,
    val contributors: List<EditableContributor>,
    val series: List<EditableSeries>,
    val genres: List<EditableGenre>,
    val tags: List<EditableTag>,
    val allGenres: List<EditableGenre>,
    val allTags: List<EditableTag>,
    val coverPath: String?,
)

/**
 * Book metadata fields that can be edited.
 *
 * Data class enables simple equality for change detection.
 * All fields are strings/primitives to match UI state directly.
 */
data class BookMetadata(
    val title: String,
    val sortTitle: String,
    val subtitle: String,
    val description: String,
    val publishYear: String,
    val publisher: String,
    val language: String?,
    val isbn: String,
    val asin: String,
    val abridged: Boolean,
    val addedAt: Long?,
)

/**
 * Request to update a book's editable fields.
 *
 * Passed to [UpdateBookUseCase] along with the original [BookEditData]
 * for change detection.
 */
data class BookUpdateRequest(
    val bookId: String,
    val metadata: BookMetadata,
    val contributors: List<EditableContributor>,
    val series: List<EditableSeries>,
    val genres: List<EditableGenre>,
    val tags: List<EditableTag>,
    val pendingCover: PendingCover?,
)

/**
 * Cover image data pending upload.
 *
 * Stored until save is confirmed, then committed to storage and uploaded.
 */
data class PendingCover(
    val data: ByteArray,
    val filename: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingCover) return false
        return data.contentEquals(other.data) && filename == other.filename
    }

    override fun hashCode(): Int = 31 * data.contentHashCode() + filename.hashCode()
}

/**
 * Type alias for original state used in change detection.
 */
typealias BookOriginalState = BookEditData
