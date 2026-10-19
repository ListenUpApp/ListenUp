package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.PageHeader
import org.jetbrains.compose.web.dom.Div

/**
 * `/book/{id}/map`: the chapter map, its selection inspector and the full table — what `?tab=chapters` used to show,
 * now one link away from the one-page Book Detail. The selection is `?sel=`, as it was.
 */
@Composable
internal fun ChapterMapPage(
    state: BookDetailUiState,
    selection: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    onOpenBook: () -> Unit,
    onOpenLibrary: () -> Unit,
    onEditChapters: () -> Unit,
) {
    val ready = state as? BookDetailUiState.Ready
    Div(attrs = { classes("bd", "bd-map") }) {
        SubPageHeader(ready, CHAPTER_MAP, onOpenBook, onOpenLibrary)
        ready?.let {
            ChaptersPane(
                chapters = it.chapters.toWebChapters(),
                selection = selection,
                onSelectionChange = onSelectionChange,
                // Absent for a reader who may not edit metadata: the editor's save would be refused.
                onEditChapters = onEditChapters.takeIf { _ -> it.canEditMetadata },
            )
        }
    }
}

/** `/book/{id}/files`: the audio files, their specs and the documents — what `?tab=files` used to show. */
@Composable
internal fun BookFilesPage(
    state: BookDetailUiState,
    documents: List<BookDocument>,
    onOpenBook: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val ready = state as? BookDetailUiState.Ready
    Div(attrs = { classes("bd", "bd-files") }) {
        SubPageHeader(ready, FILES_AND_AUDIO, onOpenBook, onOpenLibrary)
        ready?.let { FilesPane(it, documents) }
    }
}

/** Library / the book / this page: the way back up, and the page's heading. */
@Composable
private fun SubPageHeader(
    ready: BookDetailUiState.Ready?,
    title: String,
    onOpenBook: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Breadcrumb(listOf("Library", ready?.book?.title ?: "Book", title)) { index ->
        if (index == 0) onOpenLibrary() else onOpenBook()
    }
    PageHeader(title = title)
}

private const val CHAPTER_MAP = "Chapter map"

private const val FILES_AND_AUDIO = "Files & audio"
