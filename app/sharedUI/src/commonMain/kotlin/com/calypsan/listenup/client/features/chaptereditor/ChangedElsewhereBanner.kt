package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_changed_elsewhere_body
import listenup.composeapp.generated.resources.chapter_editor_changed_elsewhere_title
import org.jetbrains.compose.resources.stringResource

private val ICON_SIZE = 20.dp

/**
 * Someone else changed this book's chapters while you were editing it.
 *
 * Shown from `changedElsewhere`, which is derived rather than latched — it is true exactly while
 * the draft's fork point disagrees with the mirror, so this appears the moment a sync frame lands
 * and disappears by itself when the user saves or resets. Nothing has to remember to clear it.
 *
 * **Deliberately has no action button.** The design pairs this with "Review changes", but there is
 * no diff view to review in, and the only action actually available — throw away your draft and
 * take the incoming set — would be badly mislabelled by that word and is far too destructive to
 * offer in one tap. The warning is the whole feature: the edits are safe, and the list below is
 * where you look. The action arrives with the diff view, not before it.
 *
 * It also names nobody and gives no time, because the server does not yet record who changed a
 * chapter set or when. Saying "changed on another device" is the most this can honestly claim.
 *
 * @param modifier Modifier for the banner.
 */
@Composable
fun ChangedElsewhereBanner(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Sync,
            contentDescription = null,
            tint = colors.onTertiaryContainer,
            modifier = Modifier.size(ICON_SIZE),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                stringResource(Res.string.chapter_editor_changed_elsewhere_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onTertiaryContainer,
            )
            Text(
                stringResource(Res.string.chapter_editor_changed_elsewhere_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onTertiaryContainer,
            )
        }
    }
}
