package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.coverThemed
import com.calypsan.listenup.web.design.rememberCoverThemes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuItem
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailRow
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailRows
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailColumns
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.HeldPill
import com.calypsan.listenup.web.features.admin.ReleaseToEveryoneDialog
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.features.ratings.RatingsPanel
import com.calypsan.listenup.web.features.hardcover.BookHardcoverPanel
import com.calypsan.listenup.web.features.readers.ReadersPanel
import com.calypsan.listenup.web.design.BookMarkdown
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.UnderHeading
import com.calypsan.listenup.web.design.Pill
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.match.TwoPaneWatch
import com.calypsan.listenup.web.motion.staggerChildrenOnArrival
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.w3c.dom.HTMLElement
import com.calypsan.listenup.web.design.Dd
import org.jetbrains.compose.web.dom.Div
import com.calypsan.listenup.web.design.Dl
import com.calypsan.listenup.web.design.Dt
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

/**
 * Book Detail — the Workbench layout in the Paper voice, over the shared
 * [com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel]'s state.
 *
 * Pure in [state]: everything rendered here comes from the ViewModel, and nothing is fetched from
 * this layer. That keeps the URL and layout specs able to drive any state deterministically, and
 * it is why the store wiring lives one level up in `WebAppRoot`.
 *
 * One page (spec §3.2.1 D47): below the hero, the sections in the visit's stage order (spec §2.4) — one column on a
 * phone, two on a desktop (§3.2.4), decided in Kotlin by [rememberBookDetailWide] so a section never changes column
 * and the narrow order is the stage order. The chapter map and the files are pages of their own
 * (`/book/{id}/map`, `/book/{id}/files`), reached from the Chapters header and the Details panel.
 *
 * There is a Play control and there is deliberately NO Download one. `BookDetailUiState.Ready`
 * carries `canDownload`, and it turns true on the web the moment playback becomes available — but
 * a browser cannot finish a download (`NoDownloadsService.supportsDownloads` is false), so
 * rendering that affordance would put a button on the page whose only possible outcome is nothing
 * happening.
 *
 * [onPlay] has no default for the same reason `WebAppRoot`'s `openPlayback` has none: a defaulted
 * no-op here would render a real, enabled Play button — gated on the book's own `canPlay`, which
 * knows nothing about whether a handler was supplied — and compile clean.
 *
 * [onOpenContributor] reports a tap or activation on one of the byline's names, carrying that
 * person's [BookContributor.id]. The page stays pure the same way [onEdit] and [onPlay] keep it
 * pure: navigating to that person's own page is `WebAppRoot`'s call, not this one's.
 */
@Composable
fun BookDetailPage(
    state: BookDetailUiState,
    onOpenLibrary: () -> Unit,
    onPlay: () -> Unit,
    /** Plays this book from a chapter's start (book milliseconds). No default, for the reason [onPlay] has none. */
    onPlayFromChapter: (Long) -> Unit,
    onEdit: () -> Unit = {},
    onEditChapters: () -> Unit = {},
    onMatchMetadata: () -> Unit = {},
    onOpenGenre: (String) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    onOpenMood: (String) -> Unit = {},
    onOpenContributor: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    readers: BookReadersUiState = BookReadersUiState.Loading,
    /**
     * The rating panel's state and the ViewModel's actions. Defaulted to Loading, which draws no
     * panel at all, so the no-op actions below are unreachable until a caller supplies a state.
     * [ratingStarsFocusRequest] moves focus to your stars each time it changes (after an Undo).
     */
    ratings: BookRatingsUiState = BookRatingsUiState.Loading,
    onRate: (halfStars: Int, note: String?) -> Unit = { _, _ -> },
    onClearRating: () -> Unit = {},
    onRefreshExternalRating: () -> Unit = {},
    onSetStars: (halfStars: Int) -> Unit = {},
    ratingStarsFocusRequest: Int = 0,
    /**
     * The book's supplementary documents, from the sibling flow of the same name. Defaulted empty
     * because most books have none, and a book with none renders no Documents panel at all.
     */
    documents: List<BookDocument> = emptyList(),
    /**
     * Ask the server again. No default: a defaulted no-op renders a real, enabled Retry that does
     * nothing, which is worse than the silence the offline reason exists to end.
     */
    onRetryConnection: () -> Unit,
    /** True while a play request for this book is in flight — see the Play button. */
    isPreparing: Boolean = false,
    onMarkComplete: (startedAt: Long?, finishedAt: Long) -> Unit = { _, _ -> },
    onDiscardProgress: () -> Unit = {},
    onRestart: () -> Unit = {},
    onShare: () -> Unit = {},
    /** The pickers' state comes from [state]; these open, answer and dismiss them. */
    pickers: BookPickers = BookPickers(),
    nowMs: Long = 0L,
    onOpenProfile: (String) -> Unit = {},
    onSeeAllReaders: () -> Unit = {},
    /**
     * The Hardcover panel's state, its two ways out, and Sync with Hardcover (#1541). Defaulted to Hidden, which draws no panel
     * at all, so the no-op actions are unreachable until a caller supplies a state.
     */
    hardcover: BookHardcoverUiState = BookHardcoverUiState.Hidden,
    onFindHardcoverMatch: () -> Unit = {},
    onRemoveHardcoverMatch: () -> Unit = {},
    onSetHardcoverSynced: (Boolean) -> Unit = {},
    /** The chapter map page (`/book/{id}/map`), from the Chapters header. */
    onOpenChapterMap: () -> Unit = {},
    /** The files page (`/book/{id}/files`), from the Details panel. */
    onOpenFiles: () -> Unit = {},
    bookId: String? = null,
    /**
     * Delete this book's folder from the server (admin-only; the menu gates it). The confirm dialog
     * is the page's own, so these are the ViewModel's two calls, not "open the dialog".
     */
    onDeleteBook: () -> Unit = {},
    onClearDeleteError: () -> Unit = {},
    /** Release this held book (the triage layout's primary action; the page confirms first). */
    onReleaseFromInbox: () -> Unit = {},
    /** Put a stranded book back in All Books ("Show to all members"; no confirmation, spec §7). */
    onRestoreToAllBooks: () -> Unit = {},
    /** Open an admin collection from the Visibility panel. */
    onOpenCollection: (String) -> Unit = {},
    /** The last-match row ("Details matched … · See what changed · Undo last match"), above the sections. */
    lastMatchRow: @Composable () -> Unit = {},
    /** Arrive with the Readers panel in view and its heading focused: Home's finishers line (spec §6.4). */
    landOnReaders: Boolean = false,
    /** Called once that landing has happened, so the route can drop it from the URL. */
    onLandedOnReaders: () -> Unit = {},
) {
    val root = remember { PageRoot() }
    // Kotlin decides the layout, not CSS: the two columns split the stage order, one column keeps it whole. A first
    // guess from this window, then the page's own window answers (a phone-sized frame answers for that frame).
    var pageElement by remember { mutableStateOf<HTMLElement?>(null) }
    var wide by remember { mutableStateOf(window.matchMedia(BOOK_DETAIL_WIDE_QUERY).matches) }
    TwoPaneWatch(pageElement, useTwoPane = { wide = it }, twoPaneQuery = BOOK_DETAIL_WIDE_QUERY)
    // Once per arrival, the Discover leaderboard landing's shape: cleared when the panel has landed.
    var landingOnReaders by remember(landOnReaders) { mutableStateOf(landOnReaders) }
    Div(attrs = {
        classes("bd")
        ref { element ->
            root.element = element
            pageElement = element
            onDispose {
                root.element = null
                pageElement = null
            }
        }
    }) {
        // The breadcrumb renders in every state, including the ones with no book: a page that
        // cannot show what you asked for must still show the way out of it.
        Breadcrumb(listOf("Library", crumb(state)), onNavigate = { onOpenLibrary() })

        SharedHeader(
            state = state,
            bookId = bookId,
            isPreparing = isPreparing,
            onMarkComplete = onMarkComplete,
            onShare = onShare,
            onDiscardProgress = onDiscardProgress,
            onRestart = onRestart,
            pickers = pickers,
            onPlay = onPlay,
            onEdit = onEdit,
            onEditChapters = onEditChapters,
            onMatchMetadata = onMatchMetadata,
            onOpenContributor = onOpenContributor,
            onOpenSeries = onOpenSeries,
            documents = documents,
            onDeleteBook = onDeleteBook,
            onClearDeleteError = onClearDeleteError,
            readers = readers,
            onRetryConnection = onRetryConnection,
            onOpenFiles = onOpenFiles,
        )

        when (state) {
            is BookDetailUiState.Loading -> {
                // The header (and with it the cover) is rendered ABOVE this `when`, so nothing to
                // do here — see the note on [SharedHeader].
                Unit
            }

            is BookDetailUiState.Error -> {
                PageHeader(title = ERROR_TITLE)
                // Spec §3.2.3 Error: what happened (the typed error's own message, said once under the page heading),
                // Retry only when asking again could help (`isRetryable`: a reconnect runs catch-up, and the page turns
                // Ready when the book arrives), and always the way back.
                EmptyState(title = state.error.message, icon = WebIcon.Book) {
                    if (state.error.isRetryable) {
                        Button(kind = ButtonKind.Primary, onClick = { onRetryConnection() }) { Text("Retry") }
                    }
                    Button(
                        kind = if (state.error.isRetryable) ButtonKind.Secondary else ButtonKind.Primary,
                        onClick = { onOpenLibrary() },
                    ) { Text("Back to Library") }
                }
            }

            is BookDetailUiState.Ready -> {
                FocusHeadingWhenBlocksLeave(state, root)

                // A held book is triage-only (spec §8). The panel stands above the sections; Release asks
                // first (§7).
                if (state.isHeld) {
                    HeldTriage(state, onEdit, onMatchMetadata, onEditChapters, onReleaseFromInbox)
                }

                // Under the header's actions, as the canvas draws it (A-04): the last match, while it can be undone.
                lastMatchRow()

                BookSections(
                    state = state,
                    wide = wide,
                    panes =
                        SectionPanes(
                            onOpenGenre = onOpenGenre,
                            onOpenTag = onOpenTag,
                            onOpenMood = onOpenMood,
                            ratings = ratings,
                            onRate = onRate,
                            onClearRating = onClearRating,
                            onRefreshExternalRating = onRefreshExternalRating,
                            onSetStars = onSetStars,
                            ratingStarsFocusRequest = ratingStarsFocusRequest,
                            readers = readers,
                            nowMs = nowMs,
                            onOpenProfile = onOpenProfile,
                            onSeeAllReaders = onSeeAllReaders,
                            landOnReaders = landingOnReaders,
                            onLandedOnReaders = {
                                landingOnReaders = false
                                onLandedOnReaders()
                            },
                            hardcover = hardcover,
                            onFindHardcoverMatch = onFindHardcoverMatch,
                            onRemoveHardcoverMatch = onRemoveHardcoverMatch,
                            onSetHardcoverSynced = onSetHardcoverSynced,
                            onRestoreToAllBooks = onRestoreToAllBooks,
                            onOpenCollection = onOpenCollection,
                            onAddToCollection = pickers.onShowCollectionPicker,
                            onPlayFromChapter = onPlayFromChapter,
                            onOpenChapterMap = onOpenChapterMap,
                            onOpenFiles = onOpenFiles,
                            onOpenSeries = onOpenSeries,
                            documents = documents,
                            onOpenContributor = onOpenContributor,
                        ),
                )
            }
        }
    }
}

/**
 * The book's header, rendered in every state from one call site.
 *
 * ⛔ **The single call site is the whole point, and it is load-bearing for the shared-element
 * flight.** The cover used to be rendered inside each `when (state)` branch, which meant Compose
 * destroyed and rebuilt its DOM node when Loading became Ready. A `view-transition-name` on a node
 * that is removed mid-transition has its animation **cancelled** — and with every animation gone,
 * the transition simply ends. Measured: `ready` fired at 55 ms with five animations correctly
 * configured at their full duration, and `finished` fired at 66 ms. The flight was being cut down
 * 11 ms in, by this page rendering its own content.
 *
 * Keeping one call site means Compose updates that node rather than replacing it, so the cover
 * survives the state change and the morph runs to completion.
 *
 * The cover falls back to the id from the URL while the book is still loading — a cover URL needs
 * nothing else — so the thing the reader tapped is on screen immediately, with the text filling in
 * around it.
 */
@Composable
private fun SharedHeader(
    state: BookDetailUiState,
    bookId: String?,
    isPreparing: Boolean,
    onMarkComplete: (startedAt: Long?, finishedAt: Long) -> Unit,
    onDiscardProgress: () -> Unit,
    onRestart: () -> Unit,
    onShare: () -> Unit,
    pickers: BookPickers,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onEditChapters: () -> Unit,
    onMatchMetadata: () -> Unit,
    onOpenContributor: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    documents: List<BookDocument>,
    onDeleteBook: () -> Unit,
    onClearDeleteError: () -> Unit,
    readers: BookReadersUiState,
    onRetryConnection: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    // Error renders no header at all: a page that cannot show the book must not show a cover and
    // the word "Loading" above the reason it failed. `BookDetailPanesTest` and `BookDetailTest`
    // both pin that the failure states offer their explanation and a way back, nothing else.
    if (state is BookDetailUiState.Error) return
    val ready = state as? BookDetailUiState.Ready
    val id = ready?.book?.id?.value ?: bookId ?: return

    // The hero takes its colour from the cover (spec §2.3): fetched only once the book's cover hash is known, and
    // painted from memory in the first frame on a revisit, even while the book is still loading.
    val coverTheme = rememberCoverThemes(id, ready?.book?.coverHash, isKnown = ready != null)
    Div(attrs = {
        classes("bd-head")
        coverThemed(coverTheme)
    }) {
        // Fluid inside a box CSS sizes (260px on a desktop, 210px on a phone): the size is the stylesheet's, so the
        // image rung requested, and the hero flight's destination node, stay the same at every width.
        // The scanner's flag, for an admin (spec §3.2.3), at the top of the hero; Review opens the Files view.
        if (ready?.showScanWarning == true) ScanWarning(onReview = onOpenFiles)
        Div(attrs = { classes("bd-cover") }) {
            Cover(
                title = ready?.book?.title.orEmpty(),
                imageUrl = coverUrl(id, ready?.book?.coverHash, COVER_RUNG),
                size = null,
                radius = COVER_RADIUS,
                heroName = HERO_COVER,
                heroKey = id,
                decorative = true,
                eager = true,
            )
        }
        Div(attrs = { classes("bd-tblock") }) {
            if (ready == null) {
                // The page's H1 is there from the first frame, named "Book" for a screen reader and
                // drawn as a skeleton bar — a page that is loading still says which page it is.
                PageHeader(title = crumb(state), pending = true)
            } else {
                // Where the book sits, read before what it is called (board rev 7).
                SeriesPathLines(ready.seriesPaths, onOpenSeries)
                PageHeader(title = ready.book.title, display = true)
                ready.subtitle?.takeIf { it.isNotBlank() }?.let { P(attrs = { classes("bd-subtitle") }) { Text(it) } }
                Byline(ready, onOpenContributor)
                if (ready.isHeld) HeldPill()
                Div(attrs = { classes("bd-stats") }) { Text(statsLine(ready)) }
                // The Resume unit sits directly above the actions; a held book has neither.
                if (!ready.isHeld) ResumeUnit(ready)
                // The row itself is not gated on `canPlay`: a book with no playable audio is
                // exactly the one whose metadata most needs correcting, so Edit has to survive
                // the absence of Play. It is gated on `isHeld`: a held book's hero has no Play, no
                // actions menu and no icon buttons — its only actions are the held panel's.
                if (!ready.isHeld) {
                    // Why the primary below is disabled, directly above the actions (spec §2.9).
                    if (ready.primaryAction == PrimaryAction.UnavailableOffline) OfflineReason(onRetryConnection)
                    HeroActions(
                        ready = ready,
                        isPreparing = isPreparing,
                        onMarkComplete = onMarkComplete,
                        onDiscardProgress = onDiscardProgress,
                        onRestart = onRestart,
                        onShare = onShare,
                        pickers = pickers,
                        onPlay = onPlay,
                        onEdit = onEdit,
                        onEditChapters = onEditChapters,
                        onMatchMetadata = onMatchMetadata,
                        documents = documents,
                        onDeleteBook = onDeleteBook,
                        onClearDeleteError = onClearDeleteError,
                    )
                    // Who else is reading, under the actions: one link to the Readers panel (spec §2.10).
                    (readers as? BookReadersUiState.Data)?.let { HeroSocialLine(it.summary) }
                }
            }
        }
    }
}

/**
 * The hero's action row for a book that is not held: Play, Add to shelf, and "More ▾" (spec §2.7) — Share, the
 * progress actions, Edit details, Match details and Edit chapters, Add to collection, and Delete from server.
 */
@Composable
private fun HeroActions(
    ready: BookDetailUiState.Ready,
    isPreparing: Boolean,
    onMarkComplete: (startedAt: Long?, finishedAt: Long) -> Unit,
    onDiscardProgress: () -> Unit,
    onRestart: () -> Unit,
    onShare: () -> Unit,
    pickers: BookPickers,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onEditChapters: () -> Unit,
    onMatchMetadata: () -> Unit,
    documents: List<BookDocument>,
    onDeleteBook: () -> Unit,
    onClearDeleteError: () -> Unit,
) {
    var showDeleteDialog by remember(ready.book.id) { mutableStateOf(false) }
    Div(attrs = { classes("bd-actions") }) {
        // ⛔ The button answers its own tap. Preparing a book nulls the transport
        // bar's title (nothing is loaded, so the bar is gone) — which left pressing
        // Play with no visible consequence anywhere until audio actually began. The
        // flag behind this is `preparingBookIdUi`, delayed so a fast prepare never
        // flashes; both natives make this same button their busy surface.
        // ⛔ Always drawn now, and disabled ONLY for Unavailable offline (spec N8): a primary that vanished when
        // the book could not play left the reader with no word for why. `Button` emits no `disabled` attribute
        // at all while enabled — never `disabled="false"`.
        Button(
            kind = ButtonKind.Primary,
            size = ButtonSize.Lg,
            onClick = { onPlay() },
            enabled = ready.primaryAction != PrimaryAction.UnavailableOffline && !isPreparing,
            attrs = { classes("bd-primary") },
        ) {
            Icon(if (isPreparing) WebIcon.Clock else WebIcon.Play, size = PLAY_ICON_SIZE)
            Text(primaryLabel(ready.primaryAction, isPreparing))
        }
        // Visible, not in the menu (spec §2.7): two Add to shelf controls would be one too many.
        Button(
            kind = ButtonKind.Secondary,
            size = ButtonSize.Lg,
            onClick = { pickers.onShowShelfPicker() },
            attrs = { classes("bd-shelf") },
        ) { Text("Add to shelf") }
        ready.let { loaded ->
            // Finishing asks for the days first, as it does on Android and iOS — the
            // menu item opens the question rather than answering it with "now".
            var askingFinishDates by remember { mutableStateOf(false) }
            BookActionsMenu(
                ready = loaded,
                onItem = { item ->
                    when (item) {
                        BookDetailMenuItem.Share -> {
                            onShare()
                        }

                        BookDetailMenuItem.MarkFinished -> {
                            askingFinishDates = true
                        }

                        BookDetailMenuItem.EditDetails -> {
                            onEdit()
                        }

                        BookDetailMenuItem.MatchDetails -> {
                            onMatchMetadata()
                        }

                        BookDetailMenuItem.EditChapters -> {
                            onEditChapters()
                        }

                        BookDetailMenuItem.AddToCollection -> {
                            pickers.onShowCollectionPicker()
                        }

                        BookDetailMenuItem.DeleteFromServer -> {
                            // A refusal left over from a dialog dismissed mid-flight must not
                            // greet the next attempt.
                            onClearDeleteError()
                            showDeleteDialog = true
                        }

                        // The menu asks first and confirms through onDiscardProgress / onRestart.
                        BookDetailMenuItem.MarkNotStarted, BookDetailMenuItem.Restart -> {
                            Unit
                        }
                    }
                },
                onDiscardProgress = onDiscardProgress,
                onRestart = onRestart,
            )
            BookPickerDialogs(ready = loaded, pickers = pickers)
            // Gated on isAdmin as well as the flag, as the collection picker is: the
            // dialog must not render for a member even if the flag ever leaked true.
            if (showDeleteDialog && loaded.isAdmin) {
                DeleteBookDialog(
                    ready = loaded,
                    documents = documents,
                    onConfirm = onDeleteBook,
                    // Left open on failure so the refusal is readable; success leaves
                    // the page on BookDeleted and never returns here.
                    onDismiss = {
                        showDeleteDialog = false
                        onClearDeleteError()
                    },
                )
            }
            MarkFinishedDialog(
                open = askingFinishDates,
                startedAtMs = loaded.startedAtMs,
                onConfirm = { startedAt, finishedAt ->
                    askingFinishDates = false
                    onMarkComplete(startedAt, finishedAt)
                },
                onDismiss = { askingFinishDates = false },
            )
        }
    }
}

/** Everything the sections answer to, gathered once so [BookSections] can hand each its own. */
internal class SectionPanes(
    val onOpenGenre: (String) -> Unit,
    val onOpenTag: (String) -> Unit,
    val onOpenMood: (String) -> Unit,
    val ratings: BookRatingsUiState,
    val onRate: (halfStars: Int, note: String?) -> Unit,
    val onClearRating: () -> Unit,
    val onRefreshExternalRating: () -> Unit,
    val onSetStars: (halfStars: Int) -> Unit,
    val ratingStarsFocusRequest: Int,
    val readers: BookReadersUiState,
    val nowMs: Long,
    val onOpenProfile: (String) -> Unit,
    val onSeeAllReaders: () -> Unit,
    /** Arrive on the Readers panel (Home's finishers line, spec §6.4), once; [onLandedOnReaders] says it happened. */
    val landOnReaders: Boolean,
    val onLandedOnReaders: () -> Unit,
    val hardcover: BookHardcoverUiState,
    val onFindHardcoverMatch: () -> Unit,
    val onRemoveHardcoverMatch: () -> Unit,
    val onSetHardcoverSynced: (Boolean) -> Unit,
    val onRestoreToAllBooks: () -> Unit,
    val onOpenCollection: (String) -> Unit,
    val onAddToCollection: () -> Unit,
    val onPlayFromChapter: (Long) -> Unit,
    val onOpenChapterMap: () -> Unit,
    val onOpenFiles: () -> Unit,
    val onOpenSeries: (String) -> Unit,
    /** The book's documents, for the Details panel's Maps & illustrations row. */
    val documents: List<BookDocument> = emptyList(),
    /** A person's page, from the Details panel's All credits. */
    val onOpenContributor: (String) -> Unit = {},
)

/**
 * The sections, in the visit's stage order (spec §2.4). [wide]: the two columns of [bookDetailColumns], with the
 * admins' Visibility panel atop the side column (§3.2.4 rule 4); narrow: one column in the whole order, the Visibility
 * panel first. A held book keeps About, Chapters and Details (§3.2.3).
 */
@Composable
private fun BookSections(
    state: BookDetailUiState.Ready,
    wide: Boolean,
    panes: SectionPanes,
) {
    val order = state.shownSections()
    if (wide) {
        val columns = bookDetailColumns(order)
        Div(attrs = { classes("bd-cols") }) {
            Div(attrs = {
                classes("bd-main")
                staggerChildrenOnArrival()
            }) { columns.main.forEach { section -> key(section) { BookSection(section, state, wide = true, panes) } } }
            Div(attrs = {
                classes("bd-side")
                staggerChildrenOnArrival()
            }) {
                Visibility(state, panes)
                columns.side.forEach { section -> key(section) { BookSection(section, state, wide = true, panes) } }
            }
        }
    } else {
        Div(attrs = {
            classes("bd-flow")
            staggerChildrenOnArrival()
        }) {
            Visibility(state, panes)
            order.forEach { section -> key(section) { BookSection(section, state, wide = false, panes) } }
        }
    }
}

/** The sections a page shows: a held book is triage-only, so its social sections and Series stay hidden. */
internal fun BookDetailUiState.Ready.shownSections(): List<BookDetailSection> =
    if (isHeld) {
        sectionOrder.filter {
            it == BookDetailSection.About || it == BookDetailSection.Chapters || it == BookDetailSection.Details
        }
    } else {
        sectionOrder
    }

/** Who can't see this book. Admins only — a member's visibility is null — and never for a held book. */
@Composable
private fun Visibility(
    state: BookDetailUiState.Ready,
    panes: SectionPanes,
) {
    val visibility = state.visibility
    if (state.isHeld || visibility == null) return
    VisibilityPanel(
        bookId = state.book.id.value,
        visibility = visibility,
        isRestoring = state.isRestoringToAllBooks,
        onOpenCollection = panes.onOpenCollection,
        onRestoreToAllBooks = panes.onRestoreToAllBooks,
        onAddToCollection = panes.onAddToCollection,
    )
}

/** One section. Readers, Ratings and Hardcover draw nothing when they have nothing (spec §2.4: skipped, no gap). */
@Composable
private fun BookSection(
    section: BookDetailSection,
    state: BookDetailUiState.Ready,
    wide: Boolean,
    panes: SectionPanes,
) {
    when (section) {
        BookDetailSection.Chapters -> {
            BookChaptersSection(
                bookId = state.book.id.value,
                chapters = state.chapters,
                preview = state.chapterPreview,
                canPlay = state.canPlay,
                wide = wide,
                onPlayFromChapter = panes.onPlayFromChapter,
                onOpenChapterMap = panes.onOpenChapterMap,
            )
        }

        BookDetailSection.About -> {
            AboutPanel(state, wide, panes)
        }

        BookDetailSection.Readers -> {
            ReadersPanel(
                state = panes.readers,
                nowMs = panes.nowMs,
                onOpenProfile = panes.onOpenProfile,
                onSeeAll = panes.onSeeAllReaders,
                land = panes.landOnReaders,
                onLanded = panes.onLandedOnReaders,
            )
        }

        BookDetailSection.Ratings -> {
            RatingsPanel(
                state = panes.ratings,
                onSetStars = panes.onSetStars,
                onRate = panes.onRate,
                onClear = panes.onClearRating,
                onRefreshExternal = panes.onRefreshExternalRating,
                nowMs = panes.nowMs,
                starsFocusRequest = panes.ratingStarsFocusRequest,
                isFinished = state.readingStage == ReadingStage.Finished,
            )
        }

        BookDetailSection.Hardcover -> {
            BookHardcoverPanel(
                state = panes.hardcover,
                onFindMatch = panes.onFindHardcoverMatch,
                onRemoveMatch = panes.onRemoveHardcoverMatch,
                onSetSynced = panes.onSetHardcoverSynced,
            )
        }

        BookDetailSection.Details -> {
            Panel(title = "Details") { DetailsRows(state, panes) }
        }
    }
}

/**
 * The synopsis and its facets. On a phone it clamps — four lines while deciding, two otherwise (spec §3.2.1 D29) —
 * with "Read more" only when the clamp actually hides something; a desktop shows it whole.
 */
@Composable
private fun AboutPanel(
    state: BookDetailUiState.Ready,
    wide: Boolean,
    panes: SectionPanes,
) {
    val clampLines =
        when {
            wide -> null
            state.readingStage == ReadingStage.NotStarted -> ABOUT_LINES_NOT_STARTED
            else -> ABOUT_LINES
        }
    // Bare, under the board's large heading: About is reading, not a card of reference facts (as Chapters).
    Section(attrs = { classes("bd-about") }) {
        H2(attrs = { classes("bd-sec-title") }) { Text("About") }
        UnderHeading(level = ABOUT_HEADING_LEVEL) {
            if (state.descriptionText.isNotBlank()) ClampedDescription(state, clampLines)
            // ⛔ Every chip goes somewhere now. Before this they were inert text shaped like
            // controls, which is the worst of both — it looks pressable and answers nothing.
            FacetChips(state, panes.onOpenGenre, panes.onOpenTag, panes.onOpenMood)
            if (state.descriptionText.isBlank() && !state.hasFacets()) {
                PaneHint("No description has been written for this book.")
            }
        }
    }
}

/** The synopsis, clamped to [clampLines] (null: whole) with "Read more" only when the clamp hides something. */
@Composable
private fun ClampedDescription(
    state: BookDetailUiState.Ready,
    clampLines: Int?,
) {
    var expanded by remember(state.book.id) { mutableStateOf(false) }
    var overflows by remember(state.book.id, state.descriptionText, clampLines) { mutableStateOf(false) }
    var descElement by remember { mutableStateOf<HTMLElement?>(null) }
    val clamped = clampLines != null && !expanded
    LaunchedEffect(descElement, clampLines, expanded) {
        val element = descElement
        if (element != null && clamped) {
            // Measured after the frame that lays it out: overflowing means the clamp hides something.
            withFrameNanos { }
            overflows = element.scrollHeight > element.clientHeight + 1
        }
    }
    // The description arrives Markdown-flavoured and is untrusted external
    // metadata; [BookMarkdown] owns both halves of that.
    Div(attrs = {
        classes("bd-desc")
        if (clamped) {
            classes("is-clamped")
            style { property("--clamp", clampLines.toString()) }
        }
        ref { element ->
            descElement = element
            onDispose { descElement = null }
        }
    }) {
        BookMarkdown(state.descriptionText)
    }
    if (clampLines != null && overflows) {
        Button(attrs = {
            classes("bd-more")
            attr("type", "button")
            attr("aria-expanded", expanded.toString())
            onClick { expanded = !expanded }
        }) { Text(if (expanded) "Read less" else "Read more") }
    }
}

/** Synopsis lines on a phone before "Read more". */
private const val ABOUT_LINES_NOT_STARTED = 4

private const val ABOUT_LINES = 2

private const val ABOUT_HEADING_LEVEL = 2

/**
 * The Details panel (spec §3.2.1 D31): the shared rule's rows ([bookDetailRows]) as a description list — Publisher,
 * Released, Genre, Language, Length, Added, ISBN, ASIN — then three links: **All credits** (the credits dialog, by
 * role), **Files & audio** (the Files view) and **Maps & illustrations** (one document opens in a new tab; more go to
 * the Files view). A row whose datum is absent is not drawn, never "Unknown".
 */
@Composable
private fun DetailsRows(
    state: BookDetailUiState.Ready,
    panes: SectionPanes,
) {
    var showingCredits by remember(state.book.id) { mutableStateOf(false) }
    val bookId = state.book.id.value
    val (links, facts) = bookDetailRows(state, panes.documents).partition { it.isLink() }
    Dl(attrs = { classes("bd-details") }) {
        facts.forEach { row ->
            Div(attrs = { classes("meta-r") }) {
                Dt { Text(row.label()) }
                Dd(attrs = { if (row.isMachine()) classes("mono") }) { Text(row.value()) }
            }
        }
    }
    // Label first, as the spec writes them — "All credits (6) ›" — so each link's name says what it opens.
    Div(attrs = { classes("bd-detail-links") }) {
        links.forEach { row ->
            val text = "${row.label()} (${row.value()})"
            when (row) {
                is BookDetailRow.AllCredits -> {
                    Button(attrs = {
                        classes("bd-detail-link")
                        attr("type", BUTTON_VALUE)
                        onClick { showingCredits = true }
                    }) {
                        Text(text)
                        Span(attrs = { attr("aria-hidden", "true") }) { Text("›") }
                    }
                }

                is BookDetailRow.MapsAndIllustrations -> {
                    val only = row.onlyDocumentId?.let { id -> panes.documents.firstOrNull { it.id == id } }
                    if (only == null) FilesLink(bookId, text, panes.onOpenFiles) else DocumentLink(bookId, only, text)
                }

                else -> {
                    FilesLink(bookId, text, panes.onOpenFiles)
                }
            }
        }
    }
    if (showingCredits) {
        CreditsDialog(
            credits =
                state.book.allContributors.ifEmpty {
                    (state.book.authors + state.book.narrators).distinctBy { it.id }
                },
            onOpenContributor = panes.onOpenContributor,
            onDismiss = { showingCredits = false },
        )
    }
}

/**
 * Maps & illustrations' one document: a PDF opens in a new tab, where the browser renders it; anything else downloads,
 * because a tab of raw epub bytes helps no one (the Files view's `DocumentsPanel` makes the same call).
 */
@Composable
private fun DocumentLink(
    bookId: String,
    document: BookDocument,
    text: String,
) {
    val viewable = document.format.lowercase() == "pdf"
    A(href = documentUrl(bookId, document.id), attrs = {
        classes("bd-detail-link")
        if (viewable) {
            attr("target", "_blank")
            attr("rel", "noopener")
        } else {
            attr("download", documentBasename(document.filename))
        }
    }) {
        Text(text)
        Span(attrs = { attr("aria-hidden", "true") }) { Text(if (viewable) "↗" else "↓") }
        Span(attrs = { classes("sr-only") }) { Text(if (viewable) " (opens in a new tab)" else " (downloads)") }
    }
}

/** A Details link to the Files view (`/book/{id}/files`): a real link, followed in-app on a plain click. */
@Composable
private fun FilesLink(
    bookId: String,
    text: String,
    onOpenFiles: () -> Unit,
) {
    A(href = "/book/$bookId/files", attrs = {
        classes("bd-detail-link")
        onClick {
            it.preventDefault()
            onOpenFiles()
        }
    }) {
        Text(text)
        Span(attrs = { attr("aria-hidden", "true") }) { Text("→") }
    }
}

/** A Details row's label, in the web's words. */
private fun BookDetailRow.label(): String =
    when (this) {
        is BookDetailRow.Publisher -> "Publisher"
        is BookDetailRow.Released -> "Released"
        is BookDetailRow.GenreLine -> "Genre"
        is BookDetailRow.Language -> "Language"
        is BookDetailRow.Length -> "Length"
        is BookDetailRow.Added -> "Added"
        is BookDetailRow.Isbn -> "ISBN"
        is BookDetailRow.Asin -> "ASIN"
        is BookDetailRow.AllCredits -> "All credits"
        is BookDetailRow.FilesAndAudio -> "Files & audio"
        is BookDetailRow.MapsAndIllustrations -> "Maps & illustrations"
    }

/** A Details row's value, with its units. */
private fun BookDetailRow.value(): String =
    when (this) {
        is BookDetailRow.Publisher -> {
            name
        }

        is BookDetailRow.Released -> {
            year.toString()
        }

        is BookDetailRow.GenreLine -> {
            "$genreName · ${if (abridged) "Abridged" else "Unabridged"}"
        }

        is BookDetailRow.Language -> {
            displayName
        }

        is BookDetailRow.Length -> {
            listOfNotNull(
                DurationFormatter.hoursMinutesAlways(durationMs.milliseconds),
                chapterCount.takeIf { it > 0 }?.let { if (it == 1) "1 chapter" else "$it chapters" },
            ).joinToString(" · ")
        }

        is BookDetailRow.Added -> {
            calendarDay(epochMs, withYear = true)
        }

        is BookDetailRow.Isbn -> {
            isbn
        }

        is BookDetailRow.Asin -> {
            asin
        }

        is BookDetailRow.AllCredits -> {
            count.toString()
        }

        is BookDetailRow.FilesAndAudio -> {
            listOfNotNull(format, bitrate).joinToString(" · ")
        }

        is BookDetailRow.MapsAndIllustrations -> {
            "${format ?: if (count == 1) "1 document" else "$count documents"} · ${byteSizeLabel(totalBytes)}"
        }
    }

/** The three Details rows that open something: All credits, Files & audio, Maps & illustrations. */
private fun BookDetailRow.isLink(): Boolean =
    this is BookDetailRow.AllCredits || this is BookDetailRow.FilesAndAudio ||
        this is BookDetailRow.MapsAndIllustrations

/** Identifiers and years read in the monospace face, as the old Details list drew them. */
private fun BookDetailRow.isMachine(): Boolean =
    this is BookDetailRow.Released || this is BookDetailRow.Isbn || this is BookDetailRow.Asin

/**
 * "Author · read by Narrator", dropping either half when the book doesn't name it — same sentence
 * [byline] used to render as a flat string, but every person in it is now their own activatable
 * control carrying [BookContributor.id], not a name a caller would have to look back up.
 *
 * Renders nothing at all, not an empty `.bd-by`, when the book names neither an author nor a
 * narrator — an empty container is a blank line a reader has to explain to themselves.
 */
@Composable
private fun Byline(
    state: BookDetailUiState.Ready,
    onOpenContributor: (String) -> Unit,
) {
    val authors = state.book.authors
    val narrators = state.book.narrators
    if (authors.isEmpty() && narrators.isEmpty()) return
    var showingCredits by remember { mutableStateOf(false) }

    Div(attrs = { classes("bd-by-row") }) {
        Div(attrs = { classes("bd-by") }) {
            if (authors.isNotEmpty()) {
                ContributorNames(authors, onOpenContributor)
            }
            if (authors.isNotEmpty() && narrators.isNotEmpty()) {
                Text(" $BYLINE_SEPARATOR read by ")
            } else if (narrators.isNotEmpty()) {
                Text("read by ")
            }
            if (narrators.isNotEmpty()) {
                ContributorNames(narrators, onOpenContributor)
            }
        }
        // Everyone else credited (editors, translators, a full cast) is one press away, as on Android
        // ("All credits") and iOS (Cast & Credits). Outside `.bd-by`, so the byline still reads as
        // one sentence; the separator is drawn by CSS and is not read out.
        Button(attrs = {
            classes("bd-credits-open")
            attr("type", BUTTON_VALUE)
            onClick { showingCredits = true }
        }) { Text("All credits") }
    }

    if (showingCredits) {
        CreditsDialog(
            // Older books may carry no full credit list; the byline's people are then everyone known.
            credits = state.book.allContributors.ifEmpty { (authors + narrators).distinctBy { it.id } },
            onOpenContributor = onOpenContributor,
            onDismiss = { showingCredits = false },
        )
    }
}

/** One half of the byline: [contributors]' names, comma-joined, each its own activatable control. */
@Composable
private fun ContributorNames(
    contributors: List<BookContributor>,
    onOpen: (String) -> Unit,
) {
    contributors.forEachIndexed { index, contributor ->
        key(contributor.id) {
            if (index > 0) Text(", ")
            ContributorNameLink(contributor, onOpen)
        }
    }
}

/**
 * One person's name in the byline — focusable and keyboard-activatable to the same standard as
 * `.lib-card` and `.contrib-row`, since activating it is the same kind of navigation they offer.
 */
@Composable
private fun ContributorNameLink(
    contributor: BookContributor,
    onOpen: (String) -> Unit,
) {
    Span(attrs = {
        classes("bd-by-name")
        tabIndex(0)
        attr("role", BUTTON_VALUE)
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                onOpen(contributor.id)
            }
        }
        onClick { onOpen(contributor.id) }
    }) { Text(contributor.name) }
}

private fun crumb(state: BookDetailUiState): String = if (state is BookDetailUiState.Ready) state.book.title else "Book"

@Composable
internal fun PaneHint(text: String) {
    P(attrs = {
        style {
            property("margin", "0")
            property("font-size", "0.84375rem")
            property("color", "var(--ink-3)")
            property("font-weight", "500")
        }
    }) {
        Text(text)
    }
}

/** The byline's author/narrator divider — "Author · read by Narrator". */
private const val BYLINE_SEPARATOR = "·"

/** `type="button"` on every `<button>` here, and `role="button"` on the byline's name spans. */
private const val BUTTON_VALUE = "button"

/** Shared with the library grid's tapped tile, so the cover flies between the two. */
const val HERO_COVER = "book-cover"

/** The hero cover's derivative rung: covers the 260px desktop box at 1x and the 180px it was at 2x. */
private const val COVER_RUNG = 360

private const val COVER_RADIUS = 16

/**
 * What the primary button says (spec §2.9): Play, Resume, Listen again, or Unavailable offline.
 *
 * ⛔ "Loading…" rather than keeping "Play"/"Resume" under a changed icon: the label is what a screen
 * reader gets, and a disabled button still called "Play" says nothing about why it stopped working.
 */
internal fun primaryLabel(
    action: PrimaryAction,
    isPreparing: Boolean,
): String =
    when {
        isPreparing -> "Loading…"
        action == PrimaryAction.Resume -> "Resume"
        action == PrimaryAction.ListenAgain -> "Listen again"
        action == PrimaryAction.UnavailableOffline -> "Unavailable offline"
        else -> "Play"
    }

/** The hero's quick stats: "45h 30m · 2010 · Unabridged · 75 chapters", skipping a missing year (spec D9/D10). */
internal fun statsLine(ready: BookDetailUiState.Ready): String {
    val chapters = ready.chapters.size
    return listOfNotNull(
        DurationFormatter.hoursMinutesAlways(ready.book.duration.milliseconds),
        ready.year?.takeIf { it > 0 }?.toString(),
        if (ready.book.abridged) "Abridged" else "Unabridged",
        chapters.takeIf { it > 0 }?.let { if (it == 1) "1 chapter" else "$it chapters" },
    ).joinToString(" · ")
}

private const val PLAY_ICON_SIZE = 16

/**
 * Every genre, tag and mood on the book, each a way into the shelf of everything else like it.
 *
 * ⛔ Genres, then tags, then moods, in that order and in one row. They are three different kinds of
 * fact about a book, but a reader browsing sideways does not care which junction table a word came
 * from — they care that "Grimdark" is a door.
 */
@Composable
private fun FacetChips(
    state: BookDetailUiState.Ready,
    onOpenGenre: (String) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenMood: (String) -> Unit,
) {
    if (!state.hasFacets()) return
    Div(attrs = { classes("bd-facets") }) {
        // ⛔ Named, never a trailing lambda: [Pill]'s last parameter is `onRemove`, so a trailing
        // block hangs an X on the chip and leaves it un-pressable — which is the exact opposite of
        // the point of this row.
        state.genres.forEach { genre ->
            key(genre.id) {
                Pill(genre.name, icon = WebIcon.Layers, onClick = { onOpenGenre(genre.id) })
            }
        }
        state.tags.forEach { tag ->
            key(tag.id) {
                Pill(tag.name, icon = WebIcon.Hash, onClick = { onOpenTag(tag.id) })
            }
        }
        state.moods.forEach { mood ->
            key(mood.id) {
                Pill(mood.name, icon = WebIcon.Sparkles, onClick = { onOpenMood(mood.id) })
            }
        }
    }
}

/** Whether the book carries anything to browse sideways from. */
private fun BookDetailUiState.Ready.hasFacets(): Boolean =
    genres.isNotEmpty() || tags.isNotEmpty() || moods.isNotEmpty()

/**
 * Why the primary is disabled (spec §2.9, §3.2.3 Offline): the server is unreachable and this book cannot stream. It
 * sits in the hero directly above the actions, with Retry.
 *
 * **Not the shared copy verbatim.** Android's says the book "isn’t downloaded"; this client has no downloads at all,
 * so the web says what is true here: it can't stream.
 */
@Composable
private fun OfflineReason(onRetry: () -> Unit) {
    Div(attrs = {
        classes("bd-offline")
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        Icon(WebIcon.LinkOff, size = REASON_ICON_SIZE)
        Span { Text("Can’t reach your server, so this book can’t stream right now.") }
        Button(kind = ButtonKind.Primary, size = ButtonSize.Sm, onClick = { onRetry() }) { Text("Retry") }
    }
}

/** The scanner's flag on this book, for an admin: one sentence (the state has no per-file count, R8) and Review. */
@Composable
private fun ScanWarning(onReview: () -> Unit) {
    Div(attrs = {
        classes("banner", "err", "bd-scan")
        attr("role", "status")
    }) {
        Span { Text("The scanner flagged this book’s files.") }
        Button(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = { onReview() }) { Text("Review") }
    }
}

private const val REASON_ICON_SIZE = 18

/** Book Detail's error title, the natives' `book_detail_error_title`. */
private const val ERROR_TITLE = "Couldn’t load this book"

/** The held panel and the Release confirmation it opens. */
@Composable
private fun HeldTriage(
    state: BookDetailUiState.Ready,
    onEdit: () -> Unit,
    onMatchMetadata: () -> Unit,
    onEditChapters: () -> Unit,
    onReleaseFromInbox: () -> Unit,
) {
    var confirmingRelease by remember(state.book.id) { mutableStateOf(false) }
    HeldPanel(
        isReleasing = state.isReleasingFromInbox,
        onEdit = onEdit,
        onRelease = { confirmingRelease = true },
        onMatch = onMatchMetadata,
        onEditChapters = onEditChapters,
    )
    ReleaseToEveryoneDialog(
        open = confirmingRelease,
        bookCount = 1,
        onConfirm = {
            confirmingRelease = false
            onReleaseFromInbox()
        },
        onDismiss = { confirmingRelease = false },
    )
}

/**
 * Release (the held panel) and both stranded fixes succeed by unmounting the control that had focus;
 * the page's H1 takes it rather than `<body>`.
 */
@Composable
private fun FocusHeadingWhenBlocksLeave(
    state: BookDetailUiState.Ready,
    root: PageRoot,
) {
    FocusHeadingWhenLeft(state.book.id.value, state.isHeld) { root.element }
    FocusHeadingWhenLeft(state.book.id.value, state.visibility == BookVisibility.Stranded) { root.element }
}

/**
 * Book Detail goes to two columns at the shell's desktop breakpoint (spec §3.2.4; the `BreakpointsTest` contract).
 * Kotlin reads it, not CSS: wide splits the stage order into two columns, narrow keeps it as one list, and a CSS fold
 * of the two columns would put the side column after the main one instead of in stage order.
 */
internal const val BOOK_DETAIL_WIDE_QUERY = "(min-width: 1024px)"
