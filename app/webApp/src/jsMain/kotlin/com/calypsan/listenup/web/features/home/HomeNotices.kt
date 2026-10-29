package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val SKELETON_RAIL_CARDS = 3

/**
 * "Can't reach your server. Your library is here; playing needs the connection." with **Retry** (spec §3.1, web). A
 * tab holds no downloads, so the browser being offline and the server being unreachable read the same (C15).
 */
@Composable
internal fun ServerNotice(onRetry: () -> Unit) {
    Div(attrs = {
        classes("home-notice")
        attr("role", "status")
    }) {
        P(attrs = { classes("home-notice-t") }) {
            Span(attrs = { classes("home-notice-k") }) { Text(HomeWords.OFFLINE_NOTICE) }
            Text(" ${HomeWords.OFFLINE_NOTICE_BODY}")
        }
        Button(kind = ButtonKind.Secondary, size = ButtonSize.Sm, onClick = { onRetry() }) { Text(HomeWords.RETRY) }
    }
}

/** Continue Listening could not load (spec §3.3): one line with **Try again**, in the slot's position. */
@Composable
internal fun ContinueListeningFailedLine(onRetry: () -> Unit) {
    Div(attrs = {
        classes("home-cl-failed")
        attr("role", "status")
    }) {
        Span { Text(HomeWords.COULDNT_LOAD_LISTENING) }
        Button(kind = ButtonKind.Ghost, onClick = { onRetry() }) { Text(HomeWords.TRY_AGAIN) }
    }
}

/** Home could not load at all (spec §3.3): the slot's place says so, with **Try again**. The greeting stays above. */
@Composable
internal fun HomeErrorSlot(onRetry: () -> Unit) {
    Section(attrs = {
        classes("home-hero", "home-error")
        attr("role", "status")
    }) {
        P(attrs = { classes("home-hero-a") }) { Text(HomeWords.COULDNT_LOAD_HOME) }
        Button(kind = ButtonKind.Secondary, onClick = { onRetry() }) { Text(HomeWords.TRY_AGAIN) }
    }
}

/** The slot's skeleton (H14): a cover and three lines, while the first sync or the page is loading. */
@Composable
internal fun SlotSkeleton() {
    Section(attrs = {
        classes("home-hero")
        attr("aria-label", HomeWords.UP_NEXT_LOADING)
        attr("aria-busy", "true")
    }) {
        Div(attrs = {
            classes("home-skel-id")
            attr("aria-hidden", "true")
        }) {
            Div(attrs = { classes("skel", "home-skel-cover") })
            Div(attrs = { classes("home-skel-lines") }) {
                repeat(SKELETON_RAIL_CARDS) { Div(attrs = { classes("skel", "home-skel-line") }) }
            }
        }
    }
}

/** Page loading's rail: three card-sized skeletons, read as one "Loading books". */
@Composable
internal fun RailSkeleton() {
    Div(attrs = {
        classes("home-skel-rail")
        attr("role", "img")
        attr("aria-label", HomeWords.LOADING_BOOKS)
    }) {
        repeat(SKELETON_RAIL_CARDS) {
            Div(attrs = { classes("home-card", "is-loading") }) {
                Div(attrs = { classes("skel", "home-card-cover-skel") })
                Div(attrs = { classes("skel", "home-skel-line") })
            }
        }
    }
}
