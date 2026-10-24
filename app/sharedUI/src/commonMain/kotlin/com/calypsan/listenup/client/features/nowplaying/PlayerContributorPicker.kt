package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.features.contributors.FullCastSheet
import com.calypsan.listenup.client.presentation.player.ContributorPickerType
import com.calypsan.listenup.client.presentation.player.PlayerBook
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_cast_count_authors
import listenup.composeapp.generated.resources.book_detail_cast_count_narrators
import listenup.composeapp.generated.resources.player_sheet_authors
import listenup.composeapp.generated.resources.player_sheet_books_one
import listenup.composeapp.generated.resources.player_sheet_books_other
import listenup.composeapp.generated.resources.player_sheet_narrators
import org.jetbrains.compose.resources.stringResource

/**
 * The player's people (spec §5.5.5, PK1; `Sheets-Android`): the book's authors or narrators in the shared full-cast
 * sheet (a bottom sheet; a dialog at expanded width), titled "Authors" / "Narrators", each with how many of their books
 * are in the library ("14 books in your library"). A count of zero shows no line (C12). Book Detail's own sheet is
 * unchanged.
 *
 * @param type Which people to list.
 * @param book The loaded book.
 * @param onContributorClick A person was tapped, with their id.
 * @param onDismiss The sheet was dismissed.
 */
@Composable
internal fun PlayerContributorPicker(
    type: ContributorPickerType,
    book: PlayerBook,
    onContributorClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val people =
        when (type) {
            ContributorPickerType.AUTHORS -> book.authors
            ContributorPickerType.NARRATORS -> book.narrators
        }
    val counts = people.associate { it.id to it.bookCount }
    val booksOne = stringResource(Res.string.player_sheet_books_one)
    val booksOther = Res.string.player_sheet_books_other
    val otherLines =
        counts.values
            .filter { it > 1 }
            .distinct()
            .associateWith { stringResource(booksOther, it) }
    FullCastSheet(
        title =
            stringResource(
                when (type) {
                    ContributorPickerType.AUTHORS -> Res.string.player_sheet_authors
                    ContributorPickerType.NARRATORS -> Res.string.player_sheet_narrators
                },
            ),
        countText =
            stringResource(
                when (type) {
                    ContributorPickerType.AUTHORS -> Res.string.book_detail_cast_count_authors
                    ContributorPickerType.NARRATORS -> Res.string.book_detail_cast_count_narrators
                },
                people.size,
            ),
        contributors = people.asBookContributors(),
        onContributorClick = onContributorClick,
        onDismiss = onDismiss,
        supporting = { contributor ->
            when (val count = counts[contributor.id] ?: 0) {
                0 -> null
                1 -> booksOne
                else -> otherLines[count]
            }
        },
    )
}
