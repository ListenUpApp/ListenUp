package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from genre operations exposed through
 * [com.calypsan.listenup.api.GenreService].
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — genre failures require user
 * action (correct the input, move children first, pick a different parent, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound], [UnmappedStringNotFound] → 404
 * - [InvalidInput], [MergeSelfTarget], [MoveSelfDescendant] → 400
 * - [HasDescendants], [SlugConflict] → 409
 */
@Serializable
sealed interface GenreError : AppError {
    /**
     * No genre with the given id exists, or the genre has been soft-deleted.
     * Raised by mutations that address a specific genre when it cannot be found.
     */
    @Serializable
    @SerialName("GenreError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : GenreError {
        override val message: String = "This genre no longer exists."
        override val code: String = "GENRE_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * A supplied field value failed validation — empty name, slug normalization
     * to empty, unknown `genreId` in `setBookGenres`, or any other constraint
     * enforced at the API boundary.
     */
    @Serializable
    @SerialName("GenreError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : GenreError {
        override val message: String = "Some of the changes couldn't be saved."
        override val code: String = "GENRE_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }

    /**
     * `deleteGenre` or `mergeGenres` attempted on a genre whose subtree still
     * contains live descendants. Curator must move or delete child genres first.
     */
    @Serializable
    @SerialName("GenreError.HasDescendants")
    data class HasDescendants(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : GenreError {
        override val message: String = "Move or delete this genre's child genres first."
        override val code: String = "GENRE_HAS_DESCENDANTS"
        override val isRetryable: Boolean = false
    }

    /**
     * Returned by [com.calypsan.listenup.api.GenreService.mergeGenres] when called
     * with `source == target`. A genre can't be merged with itself.
     */
    @Serializable
    @SerialName("GenreError.MergeSelfTarget")
    data class MergeSelfTarget(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : GenreError {
        override val message: String = "A genre can't be merged with itself."
        override val code: String = "GENRE_MERGE_SELF_TARGET"
        override val isRetryable: Boolean = false
    }

    /**
     * Returned by [com.calypsan.listenup.api.GenreService.moveGenre] when the
     * requested move would place the genre under its own subtree (including
     * moving it under itself directly).
     */
    @Serializable
    @SerialName("GenreError.MoveSelfDescendant")
    data class MoveSelfDescendant(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : GenreError {
        override val message: String = "Can't move a genre under itself."
        override val code: String = "GENRE_MOVE_SELF_DESCENDANT"
        override val isRetryable: Boolean = false
    }

    /**
     * Returned by [com.calypsan.listenup.api.GenreService.mapUnmappedToGenre] when
     * no pending rows match the supplied raw string. The string may have already
     * been mapped, or it was never queued.
     */
    @Serializable
    @SerialName("GenreError.UnmappedStringNotFound")
    data class UnmappedStringNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : GenreError {
        override val message: String = "That raw string isn't in the unmapped queue."
        override val code: String = "GENRE_UNMAPPED_STRING_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * Slug derived from the supplied name conflicts with an existing live genre.
     * Raised by `createGenre` and by `updateGenre` when the new name normalizes
     * to a slug already in use.
     */
    @Serializable
    @SerialName("GenreError.SlugConflict")
    data class SlugConflict(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : GenreError {
        override val message: String = "A genre with that name already exists."
        override val code: String = "GENRE_SLUG_CONFLICT"
        override val isRetryable: Boolean = false
    }
}
