package com.calypsan.listenup.client.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_held
import listenup.composeapp.generated.resources.admin_held_a11y
import org.jetbrains.compose.resources.stringResource

/**
 * The *Held* marker: a book held for review in the admin inbox, hidden from every member.
 *
 * The design system's one badge recipe ([TonalLabel]) in the amber tertiary container — amber means
 * "waiting for you" across the app, coral stays "act here". Never colour alone: the inbox glyph and
 * the word carry it too, and a screen reader hears the whole sentence rather than "Held". Distinct
 * from the collection-visibility marker, which uses neither amber nor the inbox glyph.
 */
@Composable
fun HeldLabel(modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.admin_held_a11y)
    TonalLabel(
        label = stringResource(Res.string.admin_held),
        icon = Icons.Outlined.Inbox,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    )
}
