package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.GenreId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input row for [com.calypsan.listenup.api.BookService.setBookGenres].
 *
 * Unlike [BookContributorInput] and [BookSeriesInput], genres are **not**
 * auto-created — an unknown [genreId] surfaces as
 * [com.calypsan.listenup.api.error.BookError.InvalidInput]. The curator must
 * either pick an existing genre or create one explicitly through
 * [com.calypsan.listenup.api.GenreService.createGenre] first.
 */
@Serializable
@SerialName("BookGenreInput")
data class BookGenreInput(
    @SerialName("genreId") val genreId: GenreId,
)
