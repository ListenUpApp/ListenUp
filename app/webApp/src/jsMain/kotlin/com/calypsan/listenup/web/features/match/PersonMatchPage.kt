package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonMatchEvent
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.FocusHold
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.focusAsLanding
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * `/contributor/{id}/match` — Match details for one person, on web (W-06, W-07).
 *
 * The same page as a book's: Find | Review on a desktop, the Apply bar sticky at the bottom of the review pane;
 * under the 1024px line one column, and Review replaces Find while a person is open (W-08). The page reports
 * one pane or two to the session ([PersonMatchSession.useTwoPane]), so a desktop opens the best match at once.
 *
 * There is no role to choose: Find looks for the person in every role any source knows.
 *
 * **The keyboard path**: picking a person moves focus to the Review heading; Back returns it to their row;
 * Skip to Apply follows the heading. Focus never drops to `<body>` — each pane is a [FocusHold].
 *
 * **One polite live region** says what is happening: "Searching…", "3 people", "Applying…", "Applied",
 * failures. On [PersonMatchEvent.Applied] the page hands over to [onApplied], which returns to the
 * contributor page; its receipt takes focus there.
 */
@Suppress("LongParameterList")
@Composable
fun PersonMatchPage(
    session: PersonMatchSession,
    contributorId: String,
    viewerId: String?,
    onOpenLibrary: () -> Unit,
    onOpenContributor: () -> Unit,
    onEditByHand: () -> Unit,
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
                is PersonMatchEvent.Applied -> {
                    said = APPLIED
                    latestOnApplied()
                }

                PersonMatchEvent.ReviewReloaded -> {
                    reloadedFor = (session.reviewState.value as? PersonReviewUiState.Ready)?.candidate?.id
                    said = REVIEW_RELOADED_PERSON
                }
            }
        }
    }

    TwoPaneWatch(root, session.useTwoPane)
    PersonAnnouncements(find, review, onSay = { said = it })

    // Lands focus on whatever the last gesture asked for, once it is on screen — a hidden pane is not
    // focusable, so the request waits for it.
    LaunchedEffect(pendingFocus, find, review) {
        val id = pendingFocus ?: return@LaunchedEffect
        val target = root?.ownerDocument?.getElementById(id) as? HTMLElement ?: return@LaunchedEffect
        if (target.getClientRects().length == 0) return@LaunchedEffect
        focusAsLanding(target)
        pendingFocus = null
    }

    val name = find.header?.name
    Div(attrs = {
        classes("bmx")
        if (review !is PersonReviewUiState.NoneChosen) classes("is-reviewing")
        ref { element ->
            root = element
            onDispose { root = null }
        }
    }) {
        Breadcrumb(
            trail = listOf("Library", name ?: "Contributor", TITLE),
            onNavigate = { index -> if (index == 0) onOpenLibrary() else onOpenContributor() },
        )
        PageHeader(
            title = TITLE,
            subtitle = name,
        )
        Div(attrs = { classes("bmx-panes") }) {
            Section(attrs = {
                classes("bmx-pane", "bmx-find")
                attr("aria-labelledby", FIND_HEADING_ID)
            }) {
                FocusHold(key = personFindKey(find)) {
                    PersonFindPane(
                        find = find,
                        session = session,
                        onPick = { candidate ->
                            session.pick(candidate.key)
                            pendingFocus = REVIEW_HEADING_ID
                        },
                        onEditByHand = onEditByHand,
                    )
                }
            }
            Section(attrs = {
                classes("bmx-pane", "bmx-review")
                attr("aria-labelledby", REVIEW_HEADING_ID)
            }) {
                FocusHold(key = personReviewKey(review)) {
                    PersonReviewPane(
                        review = review,
                        personName = name.orEmpty(),
                        contributorId = contributorId,
                        viewerId = viewerId,
                        session = session,
                        reloaded =
                            reloadedFor != null &&
                                reloadedFor == (review as? PersonReviewUiState.Ready)?.candidate?.id,
                        onBack = { candidate ->
                            session.backToResults()
                            pendingFocus = rowIdOf(candidate.id)
                        },
                        onRetry = { candidate ->
                            session.pick(candidate.key)
                            pendingFocus = REVIEW_HEADING_ID
                        },
                    )
                }
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

/** Feeds the live region, keyed on what each announcement says so a ticking countdown stays silent. */
@Composable
private fun PersonAnnouncements(
    find: PersonFindUiState,
    review: PersonReviewUiState,
    onSay: (String) -> Unit,
) {
    val latestFind by rememberUpdatedState(find)
    val latestReview by rememberUpdatedState(review)
    LaunchedEffect(personFindAnnouncementKey(find)) { personFindAnnouncement(latestFind)?.let(onSay) }
    LaunchedEffect(personReviewAnnouncementKey(review)) { personReviewAnnouncement(latestReview)?.let(onSay) }
}

private fun personFindAnnouncementKey(find: PersonFindUiState): String =
    when (find) {
        is PersonFindUiState.Searching -> {
            "searching"
        }

        is PersonFindUiState.Results -> {
            "results:${find.all.joinToString { it.id }}"
        }

        is PersonFindUiState.NoProfiles -> {
            "none"
        }

        is PersonFindUiState.Failed -> {
            val failure = find.failure
            if (failure is FindFailure.RateLimited) "limited:${failure.secondsRemaining > 0}" else "failed:$failure"
        }
    }

/** What the live region says about a person's Find. */
internal fun personFindAnnouncement(find: PersonFindUiState): String? =
    when (find) {
        is PersonFindUiState.Searching -> SEARCHING
        is PersonFindUiState.Results -> peopleCountText(find.all.size)
        is PersonFindUiState.NoProfiles -> NO_PROFILES_TITLE
        is PersonFindUiState.Failed -> failureAnnouncement(find.failure)
    }

private fun personReviewAnnouncementKey(review: PersonReviewUiState): String =
    when (review) {
        PersonReviewUiState.NoneChosen -> "none"
        is PersonReviewUiState.Loading -> "loading:${review.candidate.id}"
        is PersonReviewUiState.Failed -> "failed:${review.candidate.id}"
        is PersonReviewUiState.Ready -> "ready:${review.applying}:${review.applyError}"
    }

/** What the live region says about a person's Review; null when a ready Review has nothing new to say. */
internal fun personReviewAnnouncement(review: PersonReviewUiState): String? =
    when (review) {
        PersonReviewUiState.NoneChosen -> {
            null
        }

        is PersonReviewUiState.Loading -> {
            LOADING_MATCH
        }

        is PersonReviewUiState.Failed -> {
            "Couldn't load this match. ${review.error.message}"
        }

        is PersonReviewUiState.Ready -> {
            when {
                review.applying -> APPLYING
                review.applyError != null -> nothingChanged(review.applyError!!.message)
                else -> null
            }
        }
    }

private fun personFindKey(find: PersonFindUiState): String =
    when (find) {
        is PersonFindUiState.Searching -> "searching"
        is PersonFindUiState.Results -> "results"
        is PersonFindUiState.NoProfiles -> "none"
        is PersonFindUiState.Failed -> "failed:${find.failure::class.simpleName}"
    }

private fun personReviewKey(review: PersonReviewUiState): String =
    when (review) {
        PersonReviewUiState.NoneChosen -> "none"
        is PersonReviewUiState.Loading -> "loading"
        is PersonReviewUiState.Failed -> "failed"
        is PersonReviewUiState.Ready -> "ready:${review.applying}"
    }

internal const val APPLIED = "Applied"
internal const val LOADING_MATCH = "Loading this match…"
