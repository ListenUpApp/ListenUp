package com.calypsan.listenup.web

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.model.BookEditData
import com.calypsan.listenup.client.domain.model.BookMetadata
import com.calypsan.listenup.client.presentation.bookedit.BookEditForm
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.edit.EditPhase

/** Book Edit showing [form]; [hasChanges] says whether the page holds a draft. */
fun editingBook(
    form: BookEditForm = BookEditForm(),
    phase: EditPhase = EditPhase.Idle,
    inlineError: AppError? = null,
    hasChanges: Boolean = false,
): BookEditUiState =
    BookEditUiState.Editing(
        form = form,
        original =
            BookEditData(
                bookId = form.bookId,
                metadata =
                    BookMetadata(
                        title = form.title,
                        sortTitle = form.sortTitle,
                        subtitle = form.subtitle,
                        description = form.description,
                        publishYear = form.publishYear,
                        publisher = form.publisher,
                        language = form.language,
                        isbn = form.isbn,
                        asin = form.asin,
                        abridged = form.abridged,
                        addedAt = form.addedAt,
                    ),
                contributors = form.contributors,
                series = form.series,
                genres = form.genres,
                tags = form.tags,
                allGenres = form.allGenres,
                allTags = form.allTags,
                coverPath = form.coverPath,
            ),
        phase = phase,
        inlineError = inlineError,
        hasChanges = hasChanges,
    )
