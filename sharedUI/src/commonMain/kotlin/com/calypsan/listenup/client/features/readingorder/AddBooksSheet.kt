package com.calypsan.listenup.client.features.readingorder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.readingorder.OrderBookRowUi
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_add
import listenup.composeapp.generated.resources.reading_orders_add_books

private val SHEET_LIST_MAX_HEIGHT = 420.dp
private val ROW_COVER_SIZE = 44.dp

/**
 * Modal sheet for picking series books to append to a reading order — checkbox rows over the
 * series' not-yet-member books ([books], the owner's
 * [com.calypsan.listenup.client.presentation.readingorder.ReadingOrderDetailUiState.Ready.addableBooks]).
 *
 * @param books Candidate books, in series sequence order.
 * @param onConfirm Called with the selected book ids (in [books] order) when confirmed.
 * @param onDismiss Dismisses the sheet without adding anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBooksSheet(
    books: List<OrderBookRowUi>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenMargin, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(Res.string.reading_orders_add_books),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            LazyColumn(modifier = Modifier.heightIn(max = SHEET_LIST_MAX_HEIGHT).padding(top = 8.dp)) {
                items(books, key = { it.bookId }) { book ->
                    AddBookRow(
                        book = book,
                        selected = book.bookId in selectedIds,
                        onToggle = {
                            selectedIds =
                                if (book.bookId in selectedIds) selectedIds - book.bookId else selectedIds + book.bookId
                        },
                    )
                }
            }
            Button(
                onClick = { onConfirm(books.filter { it.bookId in selectedIds }.map { it.bookId }) },
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
            ) {
                Text(stringResource(Res.string.common_add))
            }
        }
    }
}

@Composable
private fun AddBookRow(
    book: OrderBookRowUi,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        BookCoverImage(
            bookId = book.bookId,
            coverPath = null,
            contentDescription = book.title,
            title = book.title,
            author = book.authorLine,
            modifier = Modifier.size(ROW_COVER_SIZE).clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            book.authorLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
