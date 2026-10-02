package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.InboxGlyphTile
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.features.admin.inbox.inboxEntryDescription
import com.calypsan.listenup.client.features.admin.inbox.inboxEntryTitle
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_inbox_entry_subtitle
import org.jetbrains.compose.resources.stringResource

private val FanCover = 34.dp

/** Each fanned cover starts this far along from the last: a 34dp cover overlapped by 14dp (canvas). */
private val FanStep = 20.dp
private val FanAngles = listOf(-7f, 0f, 7f)

/**
 * The Library's way into the admin inbox: "Inbox · 3 new books — Waiting for you to release", an
 * amber tile, a fan of the newest held covers, and a chevron.
 *
 * Absent at zero — no placeholder, no "Inbox · 0". One button for TalkBack, named "Inbox, 3 books
 * waiting for review" (canvas). A [ContentShapes.card] in surfaceContainer, the shape of a
 * [com.calypsan.listenup.client.features.library.BookCard], so it reads as the grid's first tile
 * rather than a banner over it. Amber (tertiary) is "waiting for you"; coral stays "act here".
 *
 * @param heldCount books held for review; the entry renders nothing at 0
 * @param previewBookIds the newest held books, newest first, for the cover fan (at most three drawn)
 * @param onOpenInbox opens the inbox on the Library's own stack
 */
@Composable
fun LibraryInboxEntry(
    heldCount: Int,
    previewBookIds: List<String>,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (heldCount <= 0) return
    val haptics = LocalHaptics.current
    val description = inboxEntryDescription(heldCount)
    Surface(
        onClick = {
            haptics.press()
            onOpenInbox()
        },
        shape = ContentShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                // One control, one sentence. A contentDescription on the merged node is what
                // TalkBack speaks in place of the title and subtitle beneath it.
                .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InboxGlyphTile(size = 48.dp, corner = 16.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = inboxEntryTitle(heldCount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.admin_inbox_entry_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HeldCoverFan(bookIds = previewBookIds.take(FanAngles.size))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Up to three held covers, each [FanStep] along from the last so they overlap, gently fanned.
 * Decoration under the entry's one label. The box is exactly as wide as the fan, so the chevron
 * sits against the last cover rather than after a gap the overlap left behind.
 */
@Composable
private fun HeldCoverFan(bookIds: List<String>) {
    if (bookIds.isEmpty()) return
    val ring = MaterialTheme.colorScheme.surfaceContainer
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier =
            Modifier
                .padding(horizontal = 6.dp)
                .size(width = FanCover + FanStep * (bookIds.size - 1), height = FanCover),
    ) {
        bookIds.forEachIndexed { index, bookId ->
            BookCoverImage(
                bookId = bookId,
                coverPath = null,
                contentDescription = null,
                modifier =
                    Modifier
                        .offset(x = FanStep * index)
                        .size(FanCover)
                        .graphicsLayer { rotationZ = if (bookIds.size == 1) 0f else FanAngles[index] }
                        .clip(shape)
                        .border(2.dp, ring, shape),
            )
        }
    }
}
