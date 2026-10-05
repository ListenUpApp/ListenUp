package com.calypsan.listenup.client.features.seriesdetail.components

import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.series_in_path
import org.jetbrains.compose.resources.stringResource

/** Joins series names into a path: "Cosmere › Mistborn". The "›" carries no meaning a screen reader needs. */
internal const val SERIES_PATH_SEPARATOR = " › "

/** "1 book" / "8 books". */
@Composable
internal fun bookCountLabel(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.common_book_count, count)
    } else {
        stringResource(Res.string.common_books_count, count)
    }

/** "in Cosmere › Mistborn" — where a series sits; null for a top-level series. */
@Composable
internal fun seriesPathLabel(path: List<String>): String? =
    if (path.isEmpty()) null else stringResource(Res.string.series_in_path, path.joinToString(SERIES_PATH_SEPARATOR))

/**
 * Where a series sits and how big it is, for any list that offers series to pick or open:
 * "in Cosmere › Mistborn · 8 books", or just "8 books" for a top-level series.
 */
@Composable
internal fun seriesPlacementLine(
    path: List<String>,
    bookCount: Int,
): String {
    val books = bookCountLabel(bookCount)
    return seriesPathLabel(path)?.let { "$it · $books" } ?: books
}
