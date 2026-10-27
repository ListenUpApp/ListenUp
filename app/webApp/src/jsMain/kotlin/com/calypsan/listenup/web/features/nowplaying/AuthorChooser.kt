package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.design.AppLink
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * Go to author… for a book with several authors: each author a link to their page, with how many books they have in
 * that role. A [PlayerPanel] from More, as Volume boost is, so it hangs from More on wide and is a bottom sheet on a
 * phone, and Esc or Close hand focus back.
 */
@Composable
internal fun AuthorChooser(
    open: Boolean,
    authors: List<PlayerContributor>,
    onOpenContributor: (String) -> Unit,
    placement: PanelPlacement,
    onDismiss: () -> Unit,
) {
    PlayerPanel(
        open = open,
        title = NowPlayingWords.GO_TO_AUTHOR,
        panelClass = "author-dlg",
        placement = placement,
        onDismiss = onDismiss,
    ) {
        Ul(attrs = { classes("author-list") }) {
            authors.forEach { author ->
                key(author.id) {
                    Li {
                        AppLink(
                            href = Routes.Contributor.detail(author.id).toUrl(),
                            onOpen = { onOpenContributor(author.id) },
                            attrs = { classes("author-link") },
                        ) {
                            Span(attrs = { classes("author-n") }) { Text(author.name) }
                            PlayerSheetWords.books(author.bookCount)?.let { books ->
                                Span(attrs = { classes("author-d") }) { Text(books) }
                            }
                        }
                    }
                }
            }
        }
    }
}
