package com.calypsan.listenup.client.features.library.components

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
            InboxGlyphTile(size = GlyphTile, corner = GlyphCorner, glyphSize = GlyphSize)
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
