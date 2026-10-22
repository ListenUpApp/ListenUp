package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.LocalInDetailPane
import com.calypsan.listenup.client.design.components.HeldLabel
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuGroup
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuItem
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_back_to_library
import listenup.composeapp.generated.resources.book_detail_close
import listenup.composeapp.generated.resources.book_detail_more_actions
import org.jetbrains.compose.resources.stringResource

/**
 * Plain (non-collapsing) top app bar for the Book Detail screen.
 *
 * Back ("Back to Library") and the ⋮ "More actions" menu ([BookActionsMenu], drawn from [menu]); no title, because the
 * hero names the book right under it (spec D1: the duplicate title goes). Beside a list (see [LocalInDetailPane]) the
 * back arrow becomes a Close. Container colour is [MaterialTheme.colorScheme.surface], the surface the hero sits on, so
 * the two read as one. The ⋮ stays in the bar on every width (m3.material.io/components/top-app-bar: actions live in
 * the bar, which never scrolls away). An empty [menu] draws no ⋮ (the loading frame, the error state). A book held for
 * review ([isHeld]) has no menu: the bar shows the Held pill instead (spec §3.2.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailTopBar(
    onBackClick: () -> Unit,
    menu: List<BookDetailMenuGroup>,
    progressBusy: Boolean,
    onMenuItem: (BookDetailMenuItem) -> Unit,
    isHeld: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    var showMenu by remember { mutableStateOf(false) }
    val inDetailPane = LocalInDetailPane.current

    ListenUpTopAppBar(
        title = "",
        onBack = onBackClick,
        // Beside its list, Back no longer leaves anything — it closes this pane.
        navigationIcon = if (inDetailPane) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
        navigationContentDescription =
            stringResource(if (inDetailPane) Res.string.book_detail_close else Res.string.book_detail_back_to_library),
        actions = {
            when {
                isHeld -> {
                    HeldLabel(modifier = Modifier.padding(end = Spacing.md))
                }

                menu.isNotEmpty() -> {
                    Box {
                        IconButton(
                            onClick = {
                                haptics.press()
                                showMenu = true
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(Res.string.book_detail_more_actions),
                            )
                        }
                        BookActionsMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            groups = menu,
                            progressBusy = progressBusy,
                            onItem = onMenuItem,
                        )
                    }
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        modifier = modifier,
    )
}
