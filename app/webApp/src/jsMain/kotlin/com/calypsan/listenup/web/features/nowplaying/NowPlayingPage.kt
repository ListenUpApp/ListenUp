package com.calypsan.listenup.web.features.nowplaying

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import com.calypsan.listenup.web.design.AppLink
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.PageTitle
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverThemed
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.isPlainPrimaryClick
import com.calypsan.listenup.web.design.rememberCoverThemes
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlinx.browser.document
import kotlin.js.Date
import org.w3c.dom.HTMLElement

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
        if (loadedBook == LoadedBook.NothingLoaded && !playingReturn.isClosing) router.replace(Routes.home)
    }
    // A revoked book closes Now Playing (§5.6, C11): the shared rule, this host's way of closing.
    val closes = PlayerCards.closesNowPlaying(state) && state is PlayerUiState.Loaded
    LaunchedEffect(closes) {
        if (closes) playingReturn.close(router)
    }
    // Esc closes the page (C16): the player's ClosePlayer key, through the shell's one key dispatcher, which stands down
    // in a field and while a dialog (a picker, the palette) has the keyboard (PlayerShortcutKeys).
    // The store has answered that a book is loaded, but the player has not loaded it: give the restore a moment, then
    // go Home rather than wait on a page with nothing to play.
    val isLoaded = state is PlayerUiState.Loaded
    LaunchedEffect(loadedBook, isLoaded) {
        if (loadedBook is LoadedBook.Loaded && !isLoaded) {
            delay(RESTORE_GRACE)
            if (!playingReturn.isClosing) router.replace(Routes.home)
        }
    }
    PageTitle(NowPlayingWords.NOW_PLAYING)
    val loaded =
        state as? PlayerUiState.Loaded ?: run {
            // Never a blank screen while the book is read back: on a phone the shell has stepped aside, so the page's
            // own heading and Collapse are the way out.
            WaitingPage(onCollapse = { playingReturn.close(router) })
            return
        }
    NowPlayingPage(
        state = loaded,
        progress = playback.progress.collectAsState().value,
        actions = playback.pageActions(router = router, playingReturn = playingReturn),
        boostUnavailable = playback.boostUnavailable.collectAsState().value,
    )
}

/**
 * How long `/playing` waits, once the store has answered that a book is loaded, for the player to load it before going
 * Home: long enough for the restore's Room read, short enough that a stale entry never strands the reader.
 */
internal val RESTORE_GRACE = 2.seconds

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
        onCloseBookAndLeave = {
            playingReturn.close(router)
            onCloseBook()
        },
        onSetSpeed = onSetSpeed,
        onResetSpeed = onResetSpeed,
        onStepSpeed = onStepSpeed,
        onSetSleepTimer = onSetSleepTimer,
        onCancelSleepTimer = onCancelSleepTimer,
        onExtendSleepTimer = onExtendSleepTimer,
        onSetBoost = onSetBoost,
        onResetBoost = onResetBoost,
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
    /** Close book from More: unload, and leave the page for wherever it was opened from. */
    val onCloseBookAndLeave: () -> Unit = {},
    val onSetSpeed: (Float) -> Unit = {},
    val onResetSpeed: () -> Unit = {},
    val onStepSpeed: (Boolean) -> Unit = {},
    val onSetSleepTimer: (SleepTimerMode) -> Unit = {},
    val onCancelSleepTimer: () -> Unit = {},
    val onExtendSleepTimer: (Int) -> Unit = {},
    val onSetBoost: (Float) -> Unit = {},
    val onResetBoost: () -> Unit = {},
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
    boostUnavailable: Boolean = false,
) {
    val book = state.book
    val arrival = rememberCoverThemes(book.id, book.coverHash)
    val pickers = remember { PagePickers() }
    val card = PlayerCards.of(state)
    PagePickerDialogs(state, progress, pickers, actions, boostUnavailable)
    var pageElement by remember { mutableStateOf<HTMLElement?>(null) }
    val compact = rememberIsCompactAt(pageElement)
    Div(attrs = {
        classes("np-page")
        coverThemed(arrival)
        ref { element ->
            pageElement = element
            onDispose { pageElement = null }
        }
    }) {
        NowPlayingHeader(state, actions, compact) { item, opener ->
            when (item) {
                PlayerMenuItem.GoToBook -> actions.onOpenBook(book.id)

                is PlayerMenuItem.GoToSeries -> actions.onOpenSeries(item.seriesId)

                is PlayerMenuItem.GoToAuthor -> actions.onOpenContributor(item.authorId)

                // Several authors: a chooser of links, hung from More (a sheet below 760px), as Volume boost is.
                PlayerMenuItem.ChooseAuthor -> pickers.authors = panelPlacementFor(opener)

                // The row is the link itself: a new tab opens the document, and nothing else happens here.
                is PlayerMenuItem.OpenPdf -> Unit

                // From the More trigger: beside it from 760px, a sheet below (C9).
                is PlayerMenuItem.VolumeBoost -> pickers.boost = panelPlacementFor(opener)

                PlayerMenuItem.KeyboardShortcuts -> pickers.shortcuts = panelPlacementFor(opener)

                PlayerMenuItem.CloseBook -> actions.onCloseBookAndLeave()
            }
        }
        Div(attrs = { classes("np-cols") }) {
            Section(attrs = { classes("np-bookcol") }) {
                BookIdentity(book, state.chapter, actions)
                BookCard(state, progress)
                PhoneControls(state, progress, actions, pickers, card)
                // Wider, the bar is the transport; the card sits at the foot of the book column (§5.6).
                card?.let { Div(attrs = { classes(DESK, "np-cardslot") }) { StateCard(it, book.id, actions) } }
            }
            // A chapterless book has no list; the book column then takes the width.
            if (state.chapters.isNotEmpty()) {
                Section(attrs = {
                    classes("np-listcol", DESK)
                    attr("aria-labelledby", CHAPTERS_HEADING_ID)
                }) {
                    NowPlayingChapterList(
                        rows = state.chapters,
                        currentIndex = state.chapter.index,
                        chapterRemainingMs = progress.chapterRemainingMs,
                        chapterFraction = progress.chapterFraction,
                        bookDurationMs = book.durationMs,
                        onPick = actions.onSeekToChapter,
                    )
                }
            }
        }
        // §5.4's foot, from 1024px, where a keyboard is likely.
        P(attrs = { classes("np-foot") }) { Text(NowPlayingWords.PRESS_FOR_SHORTCUTS) }
    }
}

/** The page before its book is loaded: the heading and Collapse / Back, on the wash. */
@Composable
private fun WaitingPage(onCollapse: () -> Unit) {
    Div(attrs = { classes("np-page") }) {
        PageHeader(
            title = NowPlayingWords.NOW_PLAYING,
            leading = {
                HeaderButton(NowPlayingWords.COLLAPSE, WebIcon.ChevronDown, MOB, onCollapse)
                HeaderButton(NowPlayingWords.BACK, WebIcon.ChevronLeft, DESK, onCollapse)
            },
        )
    }
}

/** Collapse (phone) or Back (wider), the page's one heading, the status, and More: the shared [PageHeader]. */
@Composable
private fun NowPlayingHeader(
    state: PlayerUiState.Loaded,
    actions: NowPlayingActions,
    /** At the phone width: More then carries Go to series and Go to author. */
    compact: Boolean,
    onMenuItem: (PlayerMenuItem, opener: HTMLElement?) -> Unit,
) {
    PageHeader(
        title = NowPlayingWords.NOW_PLAYING,
        leading = {
            HeaderButton(NowPlayingWords.COLLAPSE, WebIcon.ChevronDown, MOB, actions.onCollapse)
            HeaderButton(NowPlayingWords.BACK, WebIcon.ChevronLeft, DESK, actions.onCollapse)
        },
        actions = {
            // Outside the heading, so the heading is never re-announced when it changes (verify 6.12). Always mounted and
            // empty without news: a live region inserted with its words already in it often goes unannounced, so only
            // the words change.
            Span(attrs = {
                classes("np-status")
                attr(ATTR_ROLE, "status")
            }) { state.status?.let { news -> Text(NowPlayingWords.status(news)) } }
            PlayerMoreMenu(state = state, compact = compact, onItem = onMenuItem)
        },
    )
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
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_LABEL, label)
        attr(ATTR_TITLE, label)
        onClick { onClick() }
    }) { Icon(icon, size = HEADER_ICON_SIZE) }
}

/** The cover, the series, the title and the people: who and what this is, every part of it a link. */
@Composable
private fun BookIdentity(
    book: PlayerBook,
    chapter: PlayerChapter,
    actions: NowPlayingActions,
) {
    val bookHref = Routes.Book.detail(book.id).toUrl()
    // Out of the tab order and the accessibility tree: the title below is the one Go to book a keyboard reaches. A
    // pointer still gets the whole cover as a target, routed in-app on a plain click like any link.
    A(href = bookHref, attrs = {
        classes("np-cover")
        attr("tabindex", "-1")
        attr(ATTR_HIDDEN, VALUE_TRUE)
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
    // Wider, the series, the authors and the narrators each have a line; a phone keeps the title, the chapter and who
    // narrates it, centred on the cover (Simon, 2026-10-27), and More carries Go to series and Go to author.
    SeriesLine(book.series, actions.onOpenSeries)
    H2(attrs = { classes("np-title") }) {
        AppLink(href = bookHref, onOpen = { actions.onOpenBook(book.id) }, attrs = {
            attr(ATTR_LABEL, NowPlayingWords.goToBook(book.title))
        }) {
            // The words in their own span, so the chevron can hang outside them on a phone and they alone sit centred.
            Span(attrs = { classes("np-title-t") }) { Text(book.title) }
            Icon(WebIcon.ChevronRight, size = TITLE_ICON_SIZE)
        }
    }
    P(attrs = { classes("np-ch-title", MOB) }) {
        Text(if (chapter.isWholeBook) NowPlayingWords.WHOLE_BOOK else NowPlayingWords.chapterTitle(chapter))
    }
    People(NowPlayingWords.BY, "np-by", book.authors, actions.onOpenContributor, widthClass = DESK)
    People(NowPlayingWords.READ_BY, "np-read-by", book.narrators, actions.onOpenContributor, widthClass = DESK)
    People(
        NowPlayingWords.NARRATED_BY,
        "np-narrated",
        book.narrators,
        actions.onOpenContributor,
        widthClass = MOB,
        icon = WebIcon.Mic,
    )
}

/** Every series the book is in, in the book's order, each with its place in that one. Absent for a standalone book. */
@Composable
private fun SeriesLine(
    series: List<PlayerSeriesRef>,
    onOpenSeries: (String) -> Unit,
) {
    if (series.isEmpty()) return
    P(attrs = { classes("np-series", DESK) }) {
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
 * the whole list as a menu of links, each with how many books that person has in this role (PK1, C12; none for zero).
 */
@Composable
private fun People(
    lead: String,
    lineClass: String,
    people: List<PlayerContributor>,
    onOpenContributor: (String) -> Unit,
    /** [MOB] or [DESK]: which widths draw this line. */
    widthClass: String,
    /** A glyph before the lead, in the line's own ink: the phone's mic. */
    icon: WebIcon? = null,
) {
    if (people.isEmpty()) return
    P(attrs = { classes(lineClass, widthClass) }) {
        icon?.let { glyph -> Span(attrs = { classes("np-by-i") }) { Icon(glyph, size = NARRATED_ICON_SIZE) } }
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
                            detail = PlayerSheetWords.books(person.bookCount),
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
    Div(attrs = { classes("np-bookcard", DESK) }) {
        P(attrs = { classes("np-ch") }) { Text(NowPlayingWords.chapterLine(state.chapter)) }
        P(attrs = { classes("np-left") }) { Text(NowPlayingWords.leftInChapter(progress.chapterRemainingMs)) }
        Div(attrs = {
            classes("np-bookbar")
            attr(ATTR_ROLE, "img")
            attr(ATTR_LABEL, "Book progress ${progress.bookPercent} percent")
        }) {
            Div(attrs = {
                classes("np-bookbar-fill")
                style { width((progress.bookFraction * PERCENT).percent) }
            })
        }
    }
}

/**
 * Which of the page's own pickers is open. The page owns them because below 760px the bar, which owns its own, is
 * hidden; Volume boost opens from More at every width.
 */
private class PagePickers {
    var speed by mutableStateOf<PanelPlacement?>(null)
    var sleep by mutableStateOf<PanelPlacement?>(null)
    var chapters by mutableStateOf<PanelPlacement?>(null)
    var boost by mutableStateOf<PanelPlacement?>(null)
    var shortcuts by mutableStateOf<PanelPlacement?>(null)
    var authors by mutableStateOf<PanelPlacement?>(null)
}

/** The four pickers, each opened over the page and leaving it underneath when it closes. */
@Composable
private fun PagePickerDialogs(
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
    pickers: PagePickers,
    actions: NowPlayingActions,
    boostUnavailable: Boolean,
) {
    val settings = state.settings
    SpeedPicker(
        open = pickers.speed != null,
        speed = settings.speed,
        defaultSpeed = settings.defaultSpeed,
        leftAtSpeedMs = progress.bookRemainingAtSpeedMs(settings.speed),
        onSet = actions.onSetSpeed,
        onStep = actions.onStepSpeed,
        onReset = actions.onResetSpeed,
        placement = pickers.speed ?: PanelPlacement.Sheet,
        onDismiss = { pickers.speed = null },
    )
    SleepTimerPicker(
        open = pickers.sleep != null,
        state = state.sleepTimer,
        chapter = state.chapter,
        chapterRemainingMs = progress.chapterRemainingMs,
        countdownMs = SleepCountdowns.remainingMs(state.sleepTimer, progress, settings.speed),
        meterFraction = SleepCountdowns.meterFraction(state.sleepTimer, progress),
        nowMs = Date.now().toLong(),
        onSet = { mode ->
            actions.onSetSleepTimer(mode)
            pickers.sleep = null
        },
        onCancel = actions.onCancelSleepTimer,
        onExtend = actions.onExtendSleepTimer,
        onKeepListening = actions.onCancelSleepTimer,
        placement = pickers.sleep ?: PanelPlacement.Sheet,
        onDismiss = { pickers.sleep = null },
    )
    ChapterPicker(
        open = pickers.chapters != null,
        chapters = state.chapters,
        chapterRemainingMs = progress.chapterRemainingMs,
        bookDurationMs = state.book.durationMs,
        onPick = { index ->
            actions.onSeekToChapter(index)
            pickers.chapters = null
        },
        placement = pickers.chapters ?: PanelPlacement.Sheet,
        onDismiss = { pickers.chapters = null },
    )
    BoostPicker(
        open = pickers.boost != null,
        boostDb = settings.boostDb,
        defaultBoostDb = settings.defaultBoostDb,
        unavailable = boostUnavailable,
        onSet = actions.onSetBoost,
        onReset = actions.onResetBoost,
        placement = pickers.boost ?: PanelPlacement.Sheet,
        onDismiss = { pickers.boost = null },
    )
    ShortcutsSheet(
        open = pickers.shortcuts != null,
        placement = pickers.shortcuts ?: PanelPlacement.Sheet,
        onDismiss = { pickers.shortcuts = null },
    )
    AuthorChooser(
        open = pickers.authors != null,
        authors = state.book.authors,
        onOpenContributor = { id ->
            pickers.authors = null
            actions.onOpenContributor(id)
        },
        placement = pickers.authors ?: PanelPlacement.Sheet,
        onDismiss = { pickers.authors = null },
    )
}

/**
 * Below 760px, where the bar is hidden: the chapter's scrubber over its times, the transport, and the settings as pills
 * (§5.2's web phone column). The chapter's own title is in the book's identity, under the title.
 */
@Composable
private fun PhoneControls(
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
    actions: NowPlayingActions,
    pickers: PagePickers,
    card: PlayerCard?,
) {
    Div(attrs = { classes("np-phone", MOB) }) {
        Div(attrs = { classes("np-scrubber") }) {
            Playhead(
                positionMs = progress.chapterPositionMs,
                durationMs = progress.chapterDurationMs,
                timeClass = "np-time",
                scrubClass = "np-scrub",
                startMs = state.chapter.startMs,
                label = NowPlayingWords.CHAPTER_POSITION,
                showsRemaining = true,
                onSeek = actions.onSeek,
                enabled = card !is PlayerCard.Problem,
            )
        }
        // The card takes the transport's place, and only the transport's (C5): the book and the settings stay.
        if (card != null) StateCard(card, state.book.id, actions) else PhoneTransport(state, actions)
        SettingsPills(state, progress, pickers)
    }
}

@Composable
private fun StateCard(
    card: PlayerCard,
    bookId: String,
    actions: NowPlayingActions,
) {
    PlayerStateCard(card = card, onAction = actions.onCardAction, onRate = actions.onRate, bookId = bookId)
}

/**
 * Previous · back N · play · forward N · next. Previous is never disabled (§3.3: in chapter one it restarts it, C18);
 * Next is, in the last chapter. The skips keep their slots while a stall disables them (C5), so the play button never
 * moves under the thumb.
 */
@Composable
private fun PhoneTransport(
    state: PlayerUiState.Loaded,
    actions: NowPlayingActions,
) {
    val settings = state.settings
    Div(attrs = { classes("np-transport") }) {
        Button(attrs = {
            classes("np-step", "np-prev")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_LABEL, PREVIOUS_CHAPTER)
            attr(ATTR_TITLE, NowPlayingWords.withKey(PREVIOUS_CHAPTER, "Shift+←"))
            attr(ATTR_KEYS, PlayerKeyAria.PREVIOUS_CHAPTER)
            attr("aria-description", NowPlayingWords.PREVIOUS_HINT)
            // A chapterless book has no previous: it would only jump to 0:00.
            if (!state.canSkipToPreviousChapter) disabled()
            onClick { actions.onPreviousChapter() }
        }) { Icon(WebIcon.ChapterPrevious, size = STEP_ICON_SIZE) }
        SkipButton(
            WebIcon.SkipBack,
            settings.skipBackSec,
            "Skip back ${settings.skipBackSec} seconds",
            "←" to PlayerKeyAria.SKIP_BACK,
            "np-skip-back",
            state.showsSkipBack,
            actions.onSkipBack,
        )
        PlayCircle(state, actions.onPrimary)
        SkipButton(
            WebIcon.SkipForward,
            settings.skipForwardSec,
            "Skip forward ${settings.skipForwardSec} seconds",
            "→" to PlayerKeyAria.SKIP_FORWARD,
            "np-skip-fwd",
            state.showsSkipBack,
            actions.onSkipForward,
        )
        Button(attrs = {
            classes("np-step", "np-next")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_LABEL, NEXT_CHAPTER)
            attr(ATTR_TITLE, NowPlayingWords.withKey(NEXT_CHAPTER, "Shift+→"))
            attr(ATTR_KEYS, PlayerKeyAria.NEXT_CHAPTER)
            if (!state.canSkipToNextChapter) disabled()
            onClick { actions.onNextChapter() }
        }) { Icon(WebIcon.ChapterNext, size = STEP_ICON_SIZE) }
    }
}

/** The 76px coral circle: the shared primary's glyph, a spinner while busy (still a Pause), a ring for Tap to play. */
@Composable
internal fun PlayCircle(
    state: PlayerUiState.Loaded,
    onPrimary: () -> Unit,
) {
    val label = primaryLabel(state.primary, state.phase)
    Button(attrs = {
        classes("np-play")
        if (state.primary == PrimaryControl.TapToPlay) classes("ring")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_LABEL, label)
        attr(ATTR_TITLE, NowPlayingWords.withKey(label, "Space"))
        attr(ATTR_KEYS, PlayerKeyAria.PLAY_PAUSE)
        onClick { onPrimary() }
    }) {
        if (state.primary == PrimaryControl.Busy) {
            Div(attrs = {
                classes("tport-wait")
                attr(ATTR_HIDDEN, VALUE_TRUE)
            })
        } else {
            Icon(primaryIcon(state.primary), size = PLAY_ICON_SIZE)
        }
    }
}

/** A skip with the listener's own interval drawn in it; the number is hidden from a screen reader, the label says it. */
@Composable
private fun SkipButton(
    icon: WebIcon,
    seconds: Int,
    label: String,
    /** The key that presses it, as the tooltip names it, and as `aria-keyshortcuts` spells it. */
    key: Pair<String, String>,
    extraClass: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("np-skip", extraClass)
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_LABEL, label)
        attr(ATTR_TITLE, NowPlayingWords.withKey(label, key.first))
        attr(ATTR_KEYS, key.second)
        if (!enabled) disabled()
        onClick { onClick() }
    }) {
        Icon(icon, size = SKIP_ICON_SIZE)
        Span(attrs = {
            classes("np-skip-n")
            attr(ATTR_HIDDEN, VALUE_TRUE)
        }) { Text(seconds.toString()) }
    }
}

/** Speed · Sleep · Chapters as outlined pills; an armed timer shows its countdown. Boost lives in More (C15). */
@Composable
private fun SettingsPills(
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
    pickers: PagePickers,
) {
    val speed = formatSpeed(state.settings.speed)
    // The wall-clock countdown every surface shares (SleepCountdowns): an end-of-chapter timer's too, never 0:00.
    val countdown =
        SleepCountdowns.remainingMs(state.sleepTimer, progress, state.settings.speed)?.let(PlayerSheetWords::countdown)
    Div(attrs = { classes("np-pills") }) {
        Pill(
            text = "$speed\u00D7",
            label = "Playback speed, $speed times",
            on = false,
            key = PillKey("< >", PlayerKeyAria.SPEED, presses = false),
        ) { opener -> pickers.speed = panelPlacementFor(opener) }
        Pill(
            text = countdown ?: "Sleep",
            label = countdown?.let { "Sleep timer, $it left" } ?: "Sleep timer",
            on = countdown != null,
            key = PillKey("T", PlayerKeyAria.SLEEP, presses = true),
        ) { pickers.sleep = panelPlacementFor(it) }
        if (state.chapters.isNotEmpty()) {
            Pill(
                text = "Chapters",
                label = null,
                on = false,
                key = PillKey("C", PlayerKeyAria.CHAPTERS, presses = true),
            ) {
                pickers.chapters = panelPlacementFor(it)
            }
        }
    }
}

@Composable
private fun Pill(
    text: String,
    label: String?,
    on: Boolean,
    key: PillKey,
    onClick: (opener: HTMLElement?) -> Unit,
) {
    Button(attrs = {
        classes("np-pill")
        if (on) classes("on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        label?.let { attr(ATTR_LABEL, it) }
        attr(ATTR_TITLE, NowPlayingWords.withKey(label ?: text, key.shown))
        attr(ATTR_KEYS, key.aria)
        if (key.presses) attr(DATA_PLAYER_KEY, key.aria)
        onClick { event -> onClick(event.currentTarget as? HTMLElement) }
    }) { Text(text) }
}

/**
 * A pill's key: [shown] in its tooltip, [aria] in `aria-keyshortcuts`; [presses] when the key presses this very control
 * (T, C) rather than acting directly (`<` and `>` step the speed without opening the panel).
 */
private class PillKey(
    val shown: String,
    val aria: String,
    val presses: Boolean,
)

private const val PREVIOUS_CHAPTER = "Previous chapter"

private const val NEXT_CHAPTER = "Next chapter"

private const val STEP_ICON_SIZE = 22

private const val SKIP_ICON_SIZE = 26

private const val PLAY_ICON_SIZE = 32

/** 2× the desktop cover's 380px. */
private const val COVER_RUNG = 760

/** The mic before "Narrated by", at the line's text size. */
private const val NARRATED_ICON_SIZE = 16

private const val COVER_RADIUS = 16

private const val HEADER_ICON_SIZE = 22

private const val TITLE_ICON_SIZE = 20

/** A byline of more than this many folds to its lead and a count. */
private const val FOLD_AFTER = 3

private const val PERCENT = 100

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val ATTR_LABEL = "aria-label"

private const val ATTR_TITLE = "title"

private const val ATTR_KEYS = "aria-keyshortcuts"

private const val ATTR_HIDDEN = "aria-hidden"

private const val ATTR_ROLE = "role"

private const val VALUE_TRUE = "true"

/** Shown from 760px, where the bar is the transport. */
private const val DESK = "np-desk"

/** Shown below 760px, where the page is the whole screen. */
private const val MOB = "np-mob"
