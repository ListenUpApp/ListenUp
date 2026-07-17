package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.AvatarStack
import com.calypsan.listenup.client.design.components.AvatarStackEntry

private val AVATAR_SIZE = 38.dp
private val SUMMARY_TEXT_SIZE = 15.sp

/**
 * "Who's in this scene" glyph cluster shown on a Story So Far entry: an overlapping [AvatarStack]
 * of the entities present, followed by an already-localized [summaryText] naming them (e.g.
 * "Eddard, Catelyn & 1 more").
 *
 * @param entries The entities present in this scene, most-prominent first.
 * @param summaryText Already-localized summary of who's present.
 * @param modifier Modifier for the row.
 */
@Composable
fun SceneStack(
    entries: List<AvatarStackEntry>,
    summaryText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarStack(entries = entries, size = AVATAR_SIZE)
        Text(
            text = summaryText,
            fontSize = SUMMARY_TEXT_SIZE,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
