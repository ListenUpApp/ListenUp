package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.library.LibrarySection
import com.calypsan.listenup.client.features.library.label
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_sections_label
import org.jetbrains.compose.resources.stringResource

/** The M3 Expressive connected group that picks a Library section (board `Main`). */
@Composable
internal fun LibrarySectionGroup(
    selected: LibrarySection,
    onSelect: (LibrarySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConnectedSelectButtonGroup(
        choices = LibrarySection.entries.map { ButtonGroupChoice(value = it, label = it.label()) },
        selected = selected,
        onSelect = onSelect,
        groupLabel = stringResource(Res.string.library_sections_label),
        modifier = modifier.padding(horizontal = Spacing.screenMargin),
    )
}
