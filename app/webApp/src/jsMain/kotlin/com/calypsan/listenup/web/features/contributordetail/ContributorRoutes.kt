package com.calypsan.listenup.web.features.contributordetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailNavAction
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditNavAction
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.web.features.admin.AdminRouteContent
import com.calypsan.listenup.web.features.bookdetail.BookRouteContent
import com.calypsan.listenup.web.features.contributoredit.ContributorEditPage
import com.calypsan.listenup.web.features.contributoredit.OpenContributorEdit
import com.calypsan.listenup.web.features.contributors.ContributorsPage
import com.calypsan.listenup.web.features.library.LibrarySession
import com.calypsan.listenup.web.features.library.routeFor
import com.calypsan.listenup.web.features.match.MatchDetailsGraph
import com.calypsan.listenup.web.features.match.PersonMatchReceipt
import com.calypsan.listenup.web.features.match.PersonMatchRoute
import com.calypsan.listenup.web.nav.BOOKS_KEY
import com.calypsan.listenup.web.nav.CONTRIBUTOR_KEY
import com.calypsan.listenup.web.nav.MATCH_KEY
import com.calypsan.listenup.web.nav.ROLE_QUERY_KEY
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.editTargetOf
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent

/**
 * `/contributor/{id}/edit` — the person's own details, and who they are really.
 *
 * Keyed on [contributorId] for the reason Contributor Detail is: `loadContributor` returns early
 * for a repeat of the id it already holds, so a session reused across two people would keep
 * showing the first.
 *
 * ⛔ **A merge can delete the contributor being edited.** The rename-collision path folds this one
 * into an existing contributor, so popping back would land on a detail page for someone who no
 * longer exists. `NavigateToMerged` carries the survivor's id precisely so the route can land
 * there instead — and it REPLACES rather than pushes, so Back does not return to the editor of a
 * contributor that is gone.
 */
@Composable
private fun ContributorEditRoute(
    router: Router,
    openContributorEdit: OpenContributorEdit,
    contributorId: String,
) {
    val session = rememberSession(contributorId) { openContributorEdit(contributorId) }

    LaunchedEffect(session) {
        session.navActions.collect { action ->
            when (action) {
                ContributorEditNavAction.NavigateBack,
                ContributorEditNavAction.SaveSuccess,
                -> {
                    // The page already asked about a draft, or it was saved: do not ask twice.
                    router.navigate(Routes.Contributor.detail(contributorId), force = true)
                }

                is ContributorEditNavAction.NavigateToMerged -> {
                    router.replace(Routes.Contributor.detail(action.contributorId.value))
                }
            }
        }
    }

    ContributorEditPage(
        state = session.state.collectAsState().value,
        mergeCandidates = session.mergeCandidates.collectAsState().value,
        onEvent = session.onEvent,
        onMergeQuery = session.onMergeQuery,
    )
}

/**
 * `/contributor/{id}/match` — Match details for one person: Find as author or narrator, Review, one Apply.
 *
 * After Apply, back to the person: their page's receipt says what changed and offers Undo. Edit by hand —
 * the way on when no source has a profile — opens the person's own form.
 */
@Composable
private fun PersonMatchDetailsRoute(
    router: Router,
    matchDetails: MatchDetailsGraph,
    currentUserId: String?,
    contributorId: String,
) {
    val person = Routes.Contributor.detail(contributorId)
    PersonMatchRoute(
        contributorId = contributorId,
        graph = matchDetails,
        viewerId = currentUserId,
        onOpenLibrary = { router.navigate(Routes.library) },
        onOpenContributor = { router.navigate(person) },
        onEditByHand = { router.navigate(Routes.Contributor.edit(contributorId)) },
        onApplied = { router.navigate(person) },
    )
}

/**
 * The contributor family: the people list, one person's page, and the form over it.
 *
 * Split out of [RouteContent] for the same reason `BookRouteContent` and `AdminRouteContent` were —
 * a family of three branches was pushing that function past the branching the build allows. The
 * line is the one the URLs already draw.
 */
@Composable
internal fun ContributorRouteContent(
    isList: Boolean,
    librarySession: LibrarySession,
    contributorId: String?,
    route: Route,
    router: Router,
    openContributorDetail: OpenContributorDetail,
    openContributorBooks: OpenContributorBooks,
    openContributorEdit: OpenContributorEdit,
    matchDetails: MatchDetailsGraph,
    currentUserId: String?,
) {
    // Derived here rather than in [RouteContent], the same way [BookRouteContent] owns its own
    // sub-routes: a value only this branch reads is this branch's business, and RouteContent's
    // length is a budget every route family spends from.
    val roleBooksContributorId = contributorId?.takeIf { route.segments.getOrNull(2) == BOOKS_KEY }
    val editingContributorId = route.editTargetOf(contributorId)
    // `/contributor/{id}/match` — Match details over one person.
    val matchingContributorId =
        if (contributorId != null && route.segments.getOrNull(2) == MATCH_KEY) contributorId else null
    val roleBooksRole = parseAnyContributorRole(route.query[ROLE_QUERY_KEY])
    val role = parseContributorRole(route.query[ROLE_QUERY_KEY])

    when {
        isList -> {
            val library = librarySession.state.collectAsState().value as? LibraryUiState.Loaded
            ContributorsPage(
                // Null until the library has actually answered, exactly as the dedicated session
                // was: an empty list and an unanswered query are different facts.
                state = library?.let { if (role == ContributorRole.NARRATOR) it.narrators else it.authors },
                role = role,
                onSelectFacet = { facet -> router.navigate(routeFor(facet)) },
                onOpenContributor = { id -> router.navigate(Routes.Contributor.detail(id)) },
                sortState =
                    library?.let {
                        if (role ==
                            ContributorRole.NARRATOR
                        ) {
                            it.narratorsSortState
                        } else {
                            it.authorsSortState
                        }
                    }
                        ?: SortState(SortCategory.NAME, SortDirection.ASCENDING),
                onEvent = librarySession.onEvent,
            )
        }

        // ⛔ Both sub-routes before the detail branch, for the same reason: `/contributor/{id}` is
        // a prefix of each, and a branch order that tests it first makes them unreachable by link.
        matchingContributorId != null -> {
            PersonMatchDetailsRoute(
                router = router,
                matchDetails = matchDetails,
                currentUserId = currentUserId,
                contributorId = matchingContributorId,
            )
        }

        // The per-role book list, likewise — `/contributor/{id}/books` is also a `/contributor/{id}`.
        roleBooksContributorId != null -> {
            ContributorBooksPage(
                state = contributorBooksState(roleBooksContributorId, roleBooksRole, openContributorBooks),
                onOpenLibrary = { router.navigate(Routes.library) },
                onOpenContributors = { router.navigate(Routes.libraryContributors) },
                onOpenContributor = { router.navigate(Routes.Contributor.detail(roleBooksContributorId)) },
                onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
            )
        }

        // The edit form, likewise.
        editingContributorId != null -> {
            ContributorEditRoute(
                router = router,
                openContributorEdit = openContributorEdit,
                contributorId = editingContributorId,
            )
        }

        contributorId != null -> {
            val session = contributorDetailSession(contributorId, openContributorDetail)

            // ⛔ Deleted is the only nav action this ViewModel emits, and it must be obeyed: the
            // page it fires from describes someone who no longer exists, so staying would leave a
            // reader looking at the remains of a person they just removed. `replace`, not
            // `navigate` — Back must not return to that page either.
            LaunchedEffect(session) {
                session.navActions.collect { action ->
                    when (action) {
                        ContributorDetailNavAction.Deleted -> {
                            router.replace(Routes.libraryContributors)
                        }
                    }
                }
            }

            val detail = session.state.collectAsState().value
            ContributorDetailPage(
                state = detail,
                onConfirmDelete = session.onConfirmDelete,
                onDismissDeleteError = session.onDismissDeleteError,
                onEdit = { router.navigate(Routes.Contributor.edit(contributorId)) },
                onMatchMetadata = { router.navigate(Routes.Contributor.match(contributorId)) },
                onOpenLibrary = { router.navigate(Routes.library) },
                onOpenContributors = { router.navigate(Routes.libraryContributors) },
                onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
                onOpenSeries = { id -> router.navigate(Routes.series(id)) },
                onOpenRoleBooks = { roleValue ->
                    router.navigate(
                        Route(
                            listOf(CONTRIBUTOR_KEY, contributorId, BOOKS_KEY),
                            mapOf(ROLE_QUERY_KEY to roleValue),
                        ),
                    )
                },
            )
            // After Match details' Apply: what changed and Undo, until dismissed.
            PersonMatchReceipt(
                contributorId = contributorId,
                name = (detail as? ContributorDetailUiState.Ready)?.contributor?.name,
                graph = matchDetails,
            )
        }
    }
}

/**
 * Opens a Contributor Books session and collects it, closing the previous one whenever either half
 * of the request changes. Keyed on both: a reader moving from a person's Author list to their
 * Narrator list has changed what they asked for, and a key on the id alone would keep answering
 * with the first role's books.
 */
@Composable
private fun contributorBooksState(
    contributorId: String,
    role: ContributorRole,
    openContributorBooks: OpenContributorBooks,
): ContributorBooksUiState {
    val session = rememberSession(contributorId, role) { openContributorBooks(contributorId, role.apiValue) }
    return session.state.collectAsState().value
}

/**
 * Opens a Contributor Detail session for [contributorId] and collects it.
 */
@Composable
private fun contributorDetailSession(
    contributorId: String,
    openContributorDetail: OpenContributorDetail,
): ContributorDetailSession {
    val session = rememberSession(contributorId) { openContributorDetail(contributorId) }
    return session
}

/**
 * Parses `?role=` for the per-role book list, where every role is a real destination.
 *
 * ⛔ Deliberately not [parseContributorRole], which folds all eight other roles into Author because
 * the Contributors list offers exactly two chips. Reusing it here would send a reader who asked for
 * a person's Editor credits to their Author ones, under a heading saying Editor.
 */
private fun parseAnyContributorRole(raw: String?): ContributorRole =
    raw?.let { ContributorRole.fromApiValue(it) } ?: ContributorRole.AUTHOR

/**
 * Parses `?role=` at the route boundary, so [ContributorsPage] only ever receives a valid enum.
 *
 * Anything other than the narrator token is Author — absent, malformed ("banana"), and even a
 * genuinely valid-but-unsupported role ("editor") alike, because the Contributors route offers
 * exactly two chips and there is no third list for a third role to fall into. A junk value must
 * never light the wrong chip while claiming the wrong empty state.
 */
private fun parseContributorRole(raw: String?): ContributorRole =
    if (raw != null && ContributorRole.fromApiValue(raw) == ContributorRole.NARRATOR) {
        ContributorRole.NARRATOR
    } else {
        ContributorRole.AUTHOR
    }
