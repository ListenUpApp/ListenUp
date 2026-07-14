package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The unified offline-first outbox payload for every book-edit surface.
 *
 * Each book edit — a metadata PATCH, a contributor/series/genre/chapter/collection
 * replace-set, or a cover removal — is one durable [BookMutation] enqueued on the
 * single `books` outbox channel. Riding one channel is load-bearing, not cosmetic:
 *
 * - **Per-entity FIFO.** The outbox orders ops by `(domainName, entityId)`, so every
 *   edit to one book must share the `books` channel to replay in the order the user
 *   made them.
 * - **One anti-flicker shield.** The in-flight shield keys on `domainName`, so a single
 *   `books` channel shields every book echo for an in-flight edit for free.
 *
 * Each variant carries exactly the arguments its backing RPC needs, mirroring the
 * corresponding [com.calypsan.listenup.api.BookService] /
 * [com.calypsan.listenup.api.CollectionService] method. All variants are last-write-wins
 * / idempotent replace-sets, so the channel is safe to re-fire.
 */
@Serializable
sealed interface BookMutation {
    /**
     * A metadata PATCH — maps to [com.calypsan.listenup.api.BookService.updateBook].
     *
     * @property patch the per-field PATCH; null fields leave existing state untouched.
     */
    @Serializable
    @SerialName("BookMutation.Update")
    data class Update(
        @SerialName("patch") val patch: BookUpdate,
    ) : BookMutation

    /**
     * A full contributor replace-set — maps to
     * [com.calypsan.listenup.api.BookService.setBookContributors].
     *
     * @property contributors the complete new contributor list (order preserved).
     */
    @Serializable
    @SerialName("BookMutation.SetContributors")
    data class SetContributors(
        @SerialName("contributors") val contributors: List<BookContributorInput>,
    ) : BookMutation

    /**
     * A full series replace-set — maps to
     * [com.calypsan.listenup.api.BookService.setBookSeries].
     *
     * @property series the complete new series list.
     */
    @Serializable
    @SerialName("BookMutation.SetSeries")
    data class SetSeries(
        @SerialName("series") val series: List<BookSeriesInput>,
    ) : BookMutation

    /**
     * A full genre replace-set — maps to
     * [com.calypsan.listenup.api.BookService.setBookGenres].
     *
     * @property genres the complete new genre list (each references an existing genre).
     */
    @Serializable
    @SerialName("BookMutation.SetGenres")
    data class SetGenres(
        @SerialName("genres") val genres: List<BookGenreInput>,
    ) : BookMutation

    /**
     * A full chapter replace-set — maps to
     * [com.calypsan.listenup.api.BookService.setBookChapters].
     *
     * @property chapters the complete new, contiguous chapter list.
     */
    @Serializable
    @SerialName("BookMutation.SetChapters")
    data class SetChapters(
        @SerialName("chapters") val chapters: List<ChapterInput>,
    ) : BookMutation

    /**
     * A replace-set of the collections a book belongs to — maps to
     * [com.calypsan.listenup.api.CollectionService.setBookCollections]. Rides the `books`
     * outbox channel (not a collection channel) so it shares the book's FIFO order and
     * anti-flicker shield; the sender dispatches it to `CollectionService`.
     *
     * @property collectionIds the complete new set of collection ids the book belongs to.
     */
    @Serializable
    @SerialName("BookMutation.SetCollections")
    data class SetCollections(
        @SerialName("collectionIds") val collectionIds: List<String>,
    ) : BookMutation

    /**
     * Remove the book's cover — maps to
     * [com.calypsan.listenup.api.BookService.deleteBookCover].
     */
    @Serializable
    @SerialName("BookMutation.DeleteCover")
    data object DeleteCover : BookMutation
}
