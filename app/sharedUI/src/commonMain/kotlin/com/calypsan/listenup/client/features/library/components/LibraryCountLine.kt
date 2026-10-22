package com.calypsan.listenup.client.features.library.components

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.library.LibraryLength
import com.calypsan.listenup.client.presentation.library.libraryLengthOf
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_book_count
import listenup.composeapp.generated.resources.library_book_count_plural
import listenup.composeapp.generated.resources.library_count_line
import listenup.composeapp.generated.resources.library_length_days
import listenup.composeapp.generated.resources.library_length_days_one
import listenup.composeapp.generated.resources.library_length_hours
import listenup.composeapp.generated.resources.library_length_hours_one
import listenup.composeapp.generated.resources.library_length_minutes
import listenup.composeapp.generated.resources.library_length_minutes_one
import org.jetbrains.compose.resources.stringResource

/**
 * "248 books · 41 days of listening" (spec §3.1.1 L4): the whole library and its length in the largest whole unit,
 * the one rule every platform shares ([libraryLengthOf]). Under a minute of audio, the books alone.
 */
@Composable
internal fun libraryCountLine(
    all: Int,
    totalMs: Long,
): String {
    val books =
        if (all == 1) {
            stringResource(Res.string.library_book_count, all)
        } else {
            stringResource(Res.string.library_book_count_plural, all)
        }
    val length =
        when (val span = libraryLengthOf(totalMs)) {
            LibraryLength.None -> {
                null
            }

            is LibraryLength.Days -> {
                if (span.count == 1L) {
                    stringResource(Res.string.library_length_days_one)
                } else {
                    stringResource(Res.string.library_length_days, span.count.toInt())
                }
            }

            is LibraryLength.Hours -> {
                if (span.count == 1L) {
                    stringResource(Res.string.library_length_hours_one)
                } else {
                    stringResource(Res.string.library_length_hours, span.count.toInt())
                }
            }

            is LibraryLength.Minutes -> {
                if (span.count == 1L) {
                    stringResource(Res.string.library_length_minutes_one)
                } else {
                    stringResource(Res.string.library_length_minutes, span.count.toInt())
                }
            }
        }
    return if (length == null) books else stringResource(Res.string.library_count_line, books, length)
}
