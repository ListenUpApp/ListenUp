package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.domain.model.BookListItem
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_hero_eyebrow
import listenup.composeapp.generated.resources.bulk_edit_hero_more
import org.jetbrains.compose.resources.stringResource

/** Each tile is the cover plus the ring that lifts it off the one behind. */
private val ClusterTileSize = 58.dp
private val ClusterRing = 2.dp
private val ClusterOverlap = (-16).dp
private val ClusterTileShape = RoundedCornerShape(14.dp)
private val ClusterCoverShape = RoundedCornerShape(12.dp)

/** How much of the hero ink the overflow chip's fill borrows. */
private const val OVERFLOW_FILL_ALPHA = 0.14f

/**
 * The bulk editor's header: what you are about to edit, made concrete.
 *
 * This is the app's canonical [ColorBlockHero] — the same colour-blocked primaryContainer block the
 * admin surfaces wear — with the selection's covers clustered underneath the title. "Edit 37 books"
 * is a number; the covers are the books. On a screen whose whole job is to stop someone editing the
 * wrong forty, showing them what they picked is not decoration.
 *
 * The cluster is a *sample*: the state carries only the first few books (see
 * `BulkEditUiState.Editing.selectionSample`) and the remainder is stated as a chip, so the header
 * costs the same whether two books were selected or four hundred.
 *
 * @param title the screen's heading, e.g. "Edit 37 books". Blank while the selection is still
 *   loading — a heading counting books nobody has read yet would be this screen's first untrue
 *   statement.
 * @param selectedCount how many books the user picked, for the eyebrow. Null while loading.
 * @param books the sampled books whose covers are shown, in the screen's order.
 * @param bookCount how many books actually loaded; the overflow chip counts the ones [books] omits.
 * @param onBack leave the screen.
 * @param modifier Modifier for the hero.
 */
@Composable
internal fun BulkEditHero(
    title: String,
    selectedCount: Int?,
    books: List<BookListItem>,
    bookCount: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ColorBlockHero(
        title = title,
        badgeIcon = Icons.Outlined.EditNote,
        onBack = onBack,
        modifier = modifier,
        overline = selectedCount?.let { stringResource(Res.string.bulk_edit_hero_eyebrow, it) },
        content =
            if (books.isEmpty()) {
                null
            } else {
                { CoverCluster(books, bookCount) }
            },
    )
}

/**
 * The selection's covers, overlapped like a hand of cards, with the remainder as a chip.
 *
 * Earlier books sit on top of later ones, so the cluster reads left to right the way the selection
 * is ordered rather than dissolving into whichever cover happened to be drawn last.
 */
@Composable
private fun CoverCluster(
    books: List<BookListItem>,
    bookCount: Int,
) {
    val remaining = bookCount - books.size
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ClusterOverlap),
    ) {
        books.forEachIndexed { index, book ->
            ClusterTile(zIndex = (books.size - index).toFloat()) {
                BookCoverImage(
                    bookId = book.id.value,
                    coverPath = book.coverPath,
                    contentDescription = null,
                    title = book.title,
                    author = book.authorNames,
                    coverHash = book.coverHash,
                    modifier = Modifier.fillMaxSize().clip(ClusterCoverShape),
                )
            }
        }
        if (remaining > 0) {
            ClusterTile(zIndex = 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(ClusterCoverShape)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = OVERFLOW_FILL_ALPHA)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.bulk_edit_hero_more, remaining),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/** One overlapped slot: a hero-coloured ring so the cover behind never bleeds into the one in front. */
@Composable
private fun ClusterTile(
    zIndex: Float,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .zIndex(zIndex)
            .size(ClusterTileSize)
            .clip(ClusterTileShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(ClusterRing),
    ) {
        content()
    }
}
