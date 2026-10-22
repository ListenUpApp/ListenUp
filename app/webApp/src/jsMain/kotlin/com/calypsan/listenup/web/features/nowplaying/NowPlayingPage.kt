package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.design.AppLink
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PageTitle
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverThemed
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.isPlainPrimaryClick
import com.calypsan.listenup.web.design.rememberCoverThemes
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * `/playing` (S4): Now Playing as a page. With nothing loaded it goes Home, replacing the entry; while the loaded book
 * is still being read back from storage it draws nothing rather than bouncing (the cold-start race: the player's state
 * starts `NothingLoaded` before the restore answers).
 */
@Composable
fun NowPlayingRoute(
    router: Router,
    playback: PlaybackSession,
    playingReturn: PlayingReturn,
) {
    val loadedBook by remember(playback) { playback.loadedBook }.collectAsState(initial = LoadedBook.Restoring)
    val state by playback.state.collectAsState()
    LaunchedEffect(loadedBook) {
        if (loadedBook == LoadedBook.NothingLoaded) router.replace(Routes.home)
    }
    PageTitle(NowPlayingWords.NOW_PLAYING)
    val loaded = state as? PlayerUiState.Loaded ?: return
    NowPlayingPage(
        state = loaded,
        progress = playback.progress.collectAsState().value,
        actions = playback.pageActions(router = router, playingReturn = playingReturn),
    )
}

/** The page's gestures, from the shell's session and the router. */
private fun PlaybackSession.pageActions(
    router: Router,
    playingReturn: PlayingReturn,
): NowPlayingActions =
    NowPlayingActions(
        onCollapse = { playingReturn.close(router) },
        onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
        onOpenSeries = { id -> router.navigate(Routes.series(id)) },
        onOpenContributor = { id -> router.navigate(Routes.Contributor.detail(id)) },
        onPrimary = onPrimary,
        onSeek = onSeek,
        onSkipBack = onSkipBack,
        onSkipForward = onSkipForward,
        onPreviousChapter = onPreviousChapter,
        onNextChapter = onNextChapter,
        onSeekToChapter = onSeekToChapter,
        onCardAction = onCardAction,
        onRate = onRate,
        onCloseBook = onCloseBook,
    )

/**
 * Every gesture the Now Playing page hands out, as lambdas, so the page renders and is specced without a router or a
 * player behind it. Each defaults to doing nothing for specs; [NowPlayingRoute] supplies every one.
 */
class NowPlayingActions(
    val onCollapse: () -> Unit = {},
    val onOpenBook: (String) -> Unit = {},
    val onOpenSeries: (String) -> Unit = {},
    val onOpenContributor: (String) -> Unit = {},
    val onPrimary: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onSkipBack: () -> Unit = {},
    val onSkipForward: () -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onSeekToChapter: (Int) -> Unit = {},
    val onCardAction: (PlayerCardAction) -> Unit = {},
    val onRate: (Int) -> Unit = {},
    val onCloseBook: () -> Unit = {},
)

/**
 * Now Playing (spec §5.4, the `NP-WebDesktop` and `NP-WebPhone` boards): one structure that CSS lays out per width.
 *
 * - Below 760px it is the whole screen, with its own chapter scrubber, transport and settings (`.np-mob`).
 * - From 760px it sits in the shell, and the bar under it is the transport (C8): the page draws a book card instead
 *   (`.np-desk`) and the chapter list, under the book until 1024px and beside it from there (§3.11).
 *
 * The cover and the title are one-click Go to book links (the title is the one a keyboard reaches; the cover is out of
 * the tab order and hidden from assistive tech, so there is one stop, not two). The page sits on the cover's wash, or
 * the player's neutral when the cover gives none (C12).
 */
@Composable
fun NowPlayingPage(
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
    actions: NowPlayingActions,
) {
    val book = state.book
    val arrival = rememberCoverThemes(book.id, book.coverHash)
    Div(attrs = {
        classes("np-page")
        coverThemed(arrival)
    }) {
        PageHeader(state, actions)
        Div(attrs = { classes("np-cols") }) {
            Section(attrs = { classes("np-bookcol") }) {
                BookIdentity(book, actions)
                BookCard(state, progress)
            }
        }
    }
}

/** Collapse (phone) or Back (wider), the heading, the status, and More. */
@Composable
private fun PageHeader(
    state: PlayerUiState.Loaded,
    actions: NowPlayingActions,
) {
    Header(attrs = { classes("np-head") }) {
        HeaderButton(NowPlayingWords.COLLAPSE, WebIcon.ChevronDown, "np-mob", actions.onCollapse)
        HeaderButton(NowPlayingWords.BACK, WebIcon.ChevronLeft, "np-desk", actions.onCollapse)
        H1(attrs = { classes("np-h1") }) { Text(NowPlayingWords.NOW_PLAYING) }
        // Outside the heading, so the heading is never re-announced when the source changes (verify 6.12).
        Span(attrs = {
            classes("np-status")
            attr("role", "status")
        }) { Text(NowPlayingWords.status(state.source)) }
    }
}

@Composable
private fun HeaderButton(
    label: String,
    icon: WebIcon,
    widthClass: String,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("np-collapse", widthClass)
        attr("type", "button")
        attr("aria-label", label)
        attr("title", label)
        onClick { onClick() }
    }) { Icon(icon, size = HEADER_ICON_SIZE) }
}

/** The cover, the series, the title and the people: who and what this is, every part of it a link. */
@Composable
private fun BookIdentity(
    book: PlayerBook,
    actions: NowPlayingActions,
) {
    val bookHref = Routes.Book.detail(book.id).toUrl()
    // Out of the tab order and the accessibility tree: the title below is the one Go to book a keyboard reaches. A
    // pointer still gets the whole cover as a target, routed in-app on a plain click like any link.
    A(href = bookHref, attrs = {
        classes("np-cover")
        attr("tabindex", "-1")
        attr("aria-hidden", "true")
        onClick { event ->
            if (event.isPlainPrimaryClick()) {
                event.preventDefault()
                actions.onOpenBook(book.id)
            }
        }
    }) {
        Cover(
            title = book.title,
            imageUrl = coverUrl(book.id, book.coverHash, COVER_RUNG),
            size = null,
            radius = COVER_RADIUS,
            decorative = true,
            eager = true,
        )
    }
    SeriesLine(book.series, actions.onOpenSeries)
    H2(attrs = { classes("np-title") }) {
        AppLink(href = bookHref, onOpen = { actions.onOpenBook(book.id) }, attrs = {
            attr("aria-label", NowPlayingWords.goToBook(book.title))
        }) {
            Text(book.title)
            Icon(WebIcon.ChevronRight, size = TITLE_ICON_SIZE)
        }
    }
    People(NowPlayingWords.BY, "np-by", book.authors, actions.onOpenContributor)
    People(NowPlayingWords.READ_BY, "np-read-by", book.narrators, actions.onOpenContributor)
}

/** Every series the book is in, in the book's order, each with its place in that one. Absent for a standalone book. */
@Composable
private fun SeriesLine(
    series: List<PlayerSeriesRef>,
    onOpenSeries: (String) -> Unit,
) {
    if (series.isEmpty()) return
    P(attrs = { classes("np-series") }) {
        series.forEachIndexed { index, membership ->
            key(membership.id) {
                if (index > 0) Span(attrs = { classes("np-sep") }) { Text(", ") }
                AppLink(href = Routes.series(membership.id).toUrl(), onOpen = { onOpenSeries(membership.id) }) {
                    Text(NowPlayingWords.seriesLink(membership.name, membership.sequenceLabel))
                }
            }
        }
    }
}

/**
 * "by" or "Read by", then each person as a link. More than three fold to the lead and a count (P8/P9, C10), which opens
 * the whole list as a menu of links; the book counts beside each name arrive with the contributor picker on every
 * platform together.
 */
@Composable
private fun People(
    lead: String,
    lineClass: String,
    people: List<PlayerContributor>,
    onOpenContributor: (String) -> Unit,
) {
    if (people.isEmpty()) return
    P(attrs = { classes(lineClass) }) {
        Span(attrs = { classes("np-by-k") }) { Text("$lead ") }
        if (people.size > FOLD_AFTER) {
            val text = NowPlayingWords.fold(people.first().name, people.size - 1)
            PopupMenu(
                items =
                    people.map { person ->
                        MenuAction(
                            label = person.name,
                            icon = null,
                            onSelect = { onOpenContributor(person.id) },
                            href = Routes.Contributor.detail(person.id).toUrl(),
                        )
                    },
                label = text,
                icon = WebIcon.ChevronDown,
                triggerClasses = arrayOf("np-fold"),
                triggerText = text,
                trailingIcon = true,
            )
        } else {
            people.forEachIndexed { index, person ->
                key(person.id) {
                    if (index > 0) Span(attrs = { classes("np-sep") }) { Text(", ") }
                    AppLink(
                        href = Routes.Contributor.detail(person.id).toUrl(),
                        onOpen = { onOpenContributor(person.id) },
                    ) { Text(person.name) }
                }
            }
        }
    }
}

/**
 * From 760px, where the bar is the transport: the chapter line, the time left in it, a passive book bar and the book
 * sentence. Read-only by design: every control that moves the book is in the bar under the page.
 */
@Composable
private fun BookCard(
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
) {
    Div(attrs = { classes("np-bookcard", "np-desk") }) {
        P(attrs = { classes("np-ch") }) { Text(NowPlayingWords.chapterLine(state.chapter)) }
        P(attrs = { classes("np-left") }) { Text(NowPlayingWords.leftInChapter(progress.chapterRemainingMs)) }
        Div(attrs = {
            classes("np-bookbar")
            attr("role", "img")
            attr("aria-label", NowPlayingWords.bookProgress(progress.bookPercent))
        }) {
            Div(attrs = {
                classes("np-bookbar-fill")
                style { width((progress.bookFraction * PERCENT).percent) }
            })
        }
        P(attrs = { classes("np-sentence") }) { Text(NowPlayingWords.bookSentence(progress)) }
    }
}

/** 2× the desktop cover's 380px. */
private const val COVER_RUNG = 760

private const val COVER_RADIUS = 16

private const val HEADER_ICON_SIZE = 22

private const val TITLE_ICON_SIZE = 20

/** A byline of more than this many folds to its lead and a count. */
private const val FOLD_AFTER = 3

private const val PERCENT = 100
