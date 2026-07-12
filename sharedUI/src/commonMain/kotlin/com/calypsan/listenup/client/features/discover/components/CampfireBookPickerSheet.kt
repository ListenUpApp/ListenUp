package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.campfire.CampfireBookPickerViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_discover_book_picker_empty
import listenup.composeapp.generated.resources.campfire_discover_book_search_placeholder
import listenup.composeapp.generated.resources.campfire_flow_create_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Bottom sheet for picking a book to start a Campfire on — the Discover "Start a campfire" tile's
 * destination ([LiveCampfiresSection]). A searchable list of the caller's own library backed by
 * [CampfireBookPickerViewModel]; tapping a row calls [onBookSelected] with its id and dismisses.
 *
 * There is no dedicated create surface here — [onBookSelected] routes to that book's detail
 * screen with the Campfire create flow pre-armed (see the `BookDetail` route's
 * `openCampfireCreate`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampfireBookPickerSheet(
    onBookSelected: (bookId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CampfireBookPickerViewModel = koinViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                text = stringResource(Res.string.campfire_flow_create_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            ListenUpTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = stringResource(Res.string.campfire_discover_book_search_placeholder),
                leadingIcon = Icons.Default.Search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (books.isEmpty()) {
                Text(
                    text = stringResource(Res.string.campfire_discover_book_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(items = books, key = { it.id.value }) { book ->
                        CampfireBookPickerRow(book = book, onClick = { onBookSelected(book.id.value) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** A single row in [CampfireBookPickerSheet]'s book list — cover thumbnail + title/author. */
@Composable
private fun CampfireBookPickerRow(
    book: BookListItem,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = book.id.value,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                contentDescription = book.title,
                title = book.title,
                author = book.authors.firstOrNull()?.name,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.authors.firstOrNull()?.name?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
