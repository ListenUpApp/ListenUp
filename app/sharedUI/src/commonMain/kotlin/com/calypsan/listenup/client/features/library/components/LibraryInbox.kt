package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.calypsan.listenup.client.design.components.BookCoverImage
import listenup.composeapp.generated.resources.library_inbox_pill_a11y
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.InboxGlyphTile
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.admin.inbox.inboxEntryDescription
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_inbox_strip
import org.jetbrains.compose.resources.stringResource

private val StripMinHeight = 48.dp
private val GlyphTile = 28.dp
private val GlyphCorner = 8.dp
private val GlyphSize = 18.dp

private val PillMinHeight = 44.dp
private val FanCover = 26.dp

/** Each fanned cover starts this far along from the last, so they overlap (board `Library-AndroidTablet`). */
private val FanStep = 15.dp
private val FanAngles = listOf(-7f, 0f, 7f)

/**
 * The phone Library's way into the admin inbox (spec a6): one line, "Inbox · 3 waiting", an amber glyph and a chevron.
 *
 * Absent at zero — no placeholder, no "Inbox · 0". One button for TalkBack, named "Inbox, 3 books waiting for
 * review". Amber (tertiary) is "waiting for you"; coral stays "act here". The grid's first scrolling item: the inbox
 * is a destination, not news about the list.
 *
 * @param heldCount books held for review; the strip renders nothing at 0
 * @param onOpenInbox opens the inbox on the Library's own stack
 */
@Composable
fun LibraryInboxStrip(
    heldCount: Int,
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
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier =
            modifier
                .fillMaxWidth()
                // One control, one sentence: TalkBack speaks this in place of the line beneath it.
                .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.heightIn(min = StripMinHeight).padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            InboxGlyphTile(size = GlyphTile, corner = GlyphCorner, glyphSize = GlyphSize, onAmber = true)
            Text(
                text = stringResource(Res.string.library_inbox_strip, heldCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(imageVector = Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

/**
 * The Android tablet's way into the admin inbox (board `Library-AndroidTablet`): a pill at the end of the first header
 * band, three fanned held covers, "Inbox · 3 waiting" and a chevron. Amber (tertiary) is "waiting for you".
 *
 * Absent at zero. One button for TalkBack, named "Inbox, 3 waiting for review"; the covers are decoration under it.
 *
 * @param heldCount books held for review; the pill renders nothing at 0
 * @param previewBookIds the newest held books, newest first; at most three are fanned
 * @param onOpenInbox opens the inbox on the Library's own stack
 */
@Composable
fun LibraryInboxPill(
    heldCount: Int,
    previewBookIds: List<String>,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (heldCount <= 0) return
    val haptics = LocalHaptics.current
    val description = stringResource(Res.string.library_inbox_pill_a11y, heldCount)
    Surface(
        onClick = {
            haptics.press()
            onOpenInbox()
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.heightIn(min = PillMinHeight).padding(start = Spacing.sm, end = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            HeldCoverFan(bookIds = previewBookIds.take(FanAngles.size))
            Text(
                text = stringResource(Res.string.library_inbox_strip, heldCount),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Icon(imageVector = Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

/**
 * Up to three held covers, each [FanStep] along from the last so they overlap, gently fanned. The box is exactly as
 * wide as the fan, so the words sit against the last cover.
 */
@Composable
private fun HeldCoverFan(bookIds: List<String>) {
    if (bookIds.isEmpty()) return
    val ring = MaterialTheme.colorScheme.tertiaryContainer
    val shape = RoundedCornerShape(6.dp)
    Box(modifier = Modifier.size(width = FanCover + FanStep * (bookIds.size - 1), height = FanCover)) {
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
                        .border(1.5.dp, ring, shape),
            )
        }
    }
}
