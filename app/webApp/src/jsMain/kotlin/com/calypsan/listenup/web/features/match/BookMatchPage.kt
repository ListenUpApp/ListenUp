package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.match.BookMatchEvent
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.FocusHold
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.focusAsLanding
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.MediaQueryList
import org.w3c.dom.events.Event

/** Which of Match details' two pages the route shows. */
enum class MatchView {
    /** Find and Review: two panes on a desktop, one column on a phone (W-01, W-03, W-08). */
    Find,

    /** Compare editions: every match beside your copy (W-02). */
    Compare,
}

/**
 * `/book/{id}/match` — Match details for one book, on web.
 *
 * Desktop: Find | Review on one page, the Apply bar sticky at the bottom of the review pane (W-01).
 * Under the 1024px line: one column, and Review replaces Find while a match is open (W-08) — the
 * stylesheet does the hiding, so the same tree serves both and focus has one place to go. The page
 * tells the ViewModel which it is ([BookMatchSession.useTwoPane]) whenever the media query flips,
 * because a two-pane layout opens the best match straight away and a phone waits for a tap.
 *
 * **The keyboard path** (spec "Web"): picking a match moves focus to the Review heading; Back
 * returns it to the row that was open; Compare's "Review this match" lands on the heading too. Focus
 * never drops to `<body>` — each pane is a [FocusHold] whose landing is its heading or the search.
 *
 * **One polite live region** says what is happening: "Searching…", "4 matches", "Applying…",
 * failures. A rate limit's countdown is announced when it starts and when it ends, never each second.
 *
 * On [BookMatchEvent.Applied] the page hands over to [onApplied], which returns to Book Detail; the
 * receipt there takes focus.
 */
@Suppress("LongParameterList")
@Composable
fun BookMatchPage(
    session: BookMatchSession,
    bookId: String,
    viewerId: String?,
    view: MatchView,
    onOpenCompare: () -> Unit,
    onCloseCompare: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBook: () -> Unit,
    onApplied: () -> Unit,
) {
    val find = session.findState.collectAsState().value
    val review = session.reviewState.collectAsState().value
    var root by remember { mutableStateOf<HTMLElement?>(null) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    var reloadedFor by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf("") }
    val latestOnApplied by rememberUpdatedState(onApplied)

    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is BookMatchEvent.Applied -> {
                    latestOnApplied()
                }

                BookMatchEvent.ReviewReloaded -> {
                    reloadedFor = (session.reviewState.value as? ReviewUiState.Ready)?.candidate?.id
                    said = REVIEW_RELOADED
                }
            }
        }
    }

    TwoPaneWatch(root, session.useTwoPane)
    Announcements(find, review, onSay = { said = it })

    // Lands focus on whatever the last gesture asked for, as soon as it is on screen: a hidden pane
    // (the phone's Find while Review is open) is not focusable, so the request waits for it.
    LaunchedEffect(pendingFocus, find, review, view) {
        val id = pendingFocus ?: return@LaunchedEffect
        val target = root?.ownerDocument?.getElementById(id) as? HTMLElement ?: return@LaunchedEffect
        if (target.getClientRects().length == 0) return@LaunchedEffect
        focusAsLanding(target)
        pendingFocus = null
    }

    val title = find.yourCopy?.title
    val reviewing = review !is ReviewUiState.NoneChosen
    Div(attrs = {
        classes("bmx")
        if (reviewing) classes("is-reviewing")
        ref { element ->
            root = element
            onDispose { root = null }
        }
    }) {
        Breadcrumb(
            trail = listOfNotNull("Library", title ?: "Book", TITLE, if (view == MatchView.Compare) COMPARE else null),
            onNavigate = { index ->
                when (index) {
                    0 -> onOpenLibrary()
                    1 -> onOpenBook()
                    else -> onCloseCompare()
                }
            },
        )
        when (view) {
            MatchView.Compare -> {
                ComparePage(
                    find = find,
                    bookId = bookId,
                    onChooseStore = session.chooseStoreForThisSearch,
                    onBack = {
                        onCloseCompare()
                        pendingFocus = COMPARE_OPEN_ID
                    },
                    onReview = { candidate ->
                        session.pick(candidate.key)
                        onCloseCompare()
                        pendingFocus = REVIEW_HEADING_ID
                    },
                )
            }

            MatchView.Find -> {
                FindAndReview(
                    find = find,
                    review = review,
                    bookId = bookId,
                    viewerId = viewerId,
                    session = session,
                    reloaded = reloadedFor != null && reloadedFor == (review as? ReviewUiState.Ready)?.candidate?.id,
                    onOpenCompare = onOpenCompare,
                    onFocus = { pendingFocus = it },
                )
            }
        }
        // The one polite live region: what is happening, in words, for a reader who cannot see it.
        Div(attrs = {
            classes("sr-only")
            attr("id", LIVE_ID)
            attr("role", "status")
            attr("aria-live", "polite")
        }) { Text(said) }
    }
}

/** Find and Review, side by side from the 1024px line and one at a time below it. */
@Suppress("LongParameterList")
@Composable
private fun FindAndReview(
    find: FindUiState,
    review: ReviewUiState,
    bookId: String,
    viewerId: String?,
    session: BookMatchSession,
    reloaded: Boolean,
    onOpenCompare: () -> Unit,
    onFocus: (String) -> Unit,
) {
    PageHeader(
        title = TITLE,
        subtitle =
            find.yourCopy?.let { copy ->
                listOf(
                    copy.title,
                    copy.authors.joinToString(", "),
                ).filter { it.isNotBlank() }.joinToString(" · ")
            },
    )
    Div(attrs = { classes("bmx-panes") }) {
        Section(attrs = {
            classes("bmx-pane", "bmx-find")
            attr("aria-labelledby", FIND_HEADING_ID)
        }) {
            FocusHold(key = findKey(find)) {
                FindPane(
                    find = find,
                    bookId = bookId,
                    session = session,
                    onPick = { candidate ->
                        session.pick(candidate.key)
                        onFocus(REVIEW_HEADING_ID)
                    },
                    onOpenCompare = onOpenCompare,
                )
            }
        }
        Section(attrs = {
            classes("bmx-pane", "bmx-review")
            attr("aria-labelledby", REVIEW_HEADING_ID)
        }) {
            FocusHold(key = reviewKey(review)) {
                ReviewPane(
                    review = review,
                    bookId = bookId,
                    viewerId = viewerId,
                    session = session,
                    reloaded = reloaded,
                    onBack = { candidate ->
                        session.backToResults()
                        onFocus(rowId(candidate))
                    },
                    onRetry = { candidate ->
                        session.pick(candidate.key)
                        onFocus(REVIEW_HEADING_ID)
                    },
                )
            }
        }
    }
}

/**
 * Reports one pane or two to the ViewModel now and whenever the layout's media query flips. Read
 * off the element's own window, so a page rendered in a phone-sized frame answers for that frame.
 */
@Composable
private fun TwoPaneWatch(
    root: HTMLElement?,
    useTwoPane: (Boolean) -> Unit,
) {
    DisposableEffect(root) {
        val window = root?.ownerDocument?.defaultView
        if (window == null) {
            onDispose { }
        } else {
            val query: MediaQueryList = window.matchMedia(TWO_PANE_QUERY)
            useTwoPane(query.matches)
            val listener: (Event) -> Unit = { useTwoPane(query.matches) }
            query.addEventListener("change", listener)
            onDispose { query.removeEventListener("change", listener) }
        }
    }
}

/**
 * Feeds the live region. Each announcement is keyed on what it says, not on the state's every
 * change, so the rate-limit countdown ticking from 0:30 to 0:01 is silent: its key only moves when
 * the wait starts and when it ends.
 */
@Composable
private fun Announcements(
    find: FindUiState,
    review: ReviewUiState,
    onSay: (String) -> Unit,
) {
    val latestFind by rememberUpdatedState(find)
    val latestReview by rememberUpdatedState(review)
    LaunchedEffect(findAnnouncementKey(find)) { findAnnouncement(latestFind)?.let(onSay) }
    LaunchedEffect(reviewAnnouncementKey(review)) { reviewAnnouncement(latestReview)?.let(onSay) }
}

private fun findAnnouncementKey(find: FindUiState): String =
    when (find) {
        is FindUiState.Searching -> {
            "searching"
        }

        is FindUiState.Results -> {
            "results:${find.all.size}:${find.all.joinToString { it.id }}"
        }

        is FindUiState.Failed -> {
            val failure = find.failure
            if (failure is FindFailure.RateLimited) "limited:${failure.secondsRemaining > 0}" else "failed:$failure"
        }
    }

/** What the live region says about Find. */
internal fun findAnnouncement(find: FindUiState): String? =
    when (find) {
        is FindUiState.Searching -> {
            SEARCHING
        }

        is FindUiState.Results -> {
            if (find.all.size == 1) "1 match" else "${find.all.size} matches"
        }

        is FindUiState.Failed -> {
            val failure = find.failure
            if (failure is FindFailure.RateLimited && failure.secondsRemaining <= 0) {
                "You can retry ${failure.source.label} now."
            } else {
                "${failureTitle(failure)}. ${failureBody(failure)}"
            }
        }
    }

private fun reviewAnnouncementKey(review: ReviewUiState): String =
    when (review) {
        ReviewUiState.NoneChosen -> "none"
        is ReviewUiState.Loading -> "loading:${review.candidate.id}"
        is ReviewUiState.Failed -> "failed:${review.candidate.id}"
        is ReviewUiState.Ready -> "ready:${review.applying}:${review.applyError}"
    }

/** What the live region says about Review; null when a ready Review has nothing new to say. */
internal fun reviewAnnouncement(review: ReviewUiState): String? =
    when (review) {
        ReviewUiState.NoneChosen -> {
            null
        }

        is ReviewUiState.Loading -> {
            "Loading this match…"
        }

        is ReviewUiState.Failed -> {
            "Couldn't load this match. ${review.error.message}"
        }

        is ReviewUiState.Ready -> {
            when {
                review.applying -> APPLYING
                review.applyError != null -> nothingChanged(review.applyError!!.message)
                else -> null
            }
        }
    }

private fun findKey(find: FindUiState): String =
    when (find) {
        is FindUiState.Searching -> "searching"
        is FindUiState.Results -> "results"
        is FindUiState.Failed -> "failed:${find.failure::class.simpleName}"
    }

private fun reviewKey(review: ReviewUiState): String =
    when (review) {
        ReviewUiState.NoneChosen -> "none"
        is ReviewUiState.Loading -> "loading"
        is ReviewUiState.Failed -> "failed"
        is ReviewUiState.Ready -> "ready:${review.applying}"
    }

/** A candidate row's element id: stable across Finds, safe in a selector. */
internal fun rowId(candidate: CandidateUi): String = "bmx-row-" + candidate.id.replace(UNSAFE_ID, "-")

internal const val TITLE = "Match details"
internal const val COMPARE = "Compare editions"
internal const val FIND_HEADING_ID = "bmx-find-h"
internal const val REVIEW_HEADING_ID = "bmx-review-h"
internal const val COMPARE_OPEN_ID = "bmx-compare-open"
internal const val APPLY_ID = "bmx-apply"
internal const val LIVE_ID = "bmx-live"
internal const val SEARCHING = "Searching…"
internal const val APPLYING = "Applying…"
internal const val REVIEW_RELOADED = "This book changed while you were reviewing. Check the changes again."

/** Two panes from the stylesheet's 1024px breakpoint (00-base.css lists the four). */
private const val TWO_PANE_QUERY = "(min-width: 1024px)"
private val UNSAFE_ID = Regex("[^A-Za-z0-9_-]")
