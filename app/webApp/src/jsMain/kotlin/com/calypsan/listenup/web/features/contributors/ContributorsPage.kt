package com.calypsan.listenup.web.features.contributors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.FacetRow
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.SortDropdown
import com.calypsan.listenup.web.design.VirtualList
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.avatarTintFor
import com.calypsan.listenup.web.design.ContributorPhoto
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import com.calypsan.listenup.web.motion.CoverSurface
import com.calypsan.listenup.web.motion.contributorHeroKey
import com.calypsan.listenup.web.motion.heroTile
import com.calypsan.listenup.web.motion.recordHeroOrigin
import org.w3c.dom.Element

/**
 * The Contributors list — every author and narrator in the library, A to Z.
 *
 * Renders [state] and nothing else: which role is showing, and the count in the header pill, are
 * both facts the caller already knows rather than something this page infers. The facet row here
 * only reports the gesture ([onSelectFacet]) — which flow is actually being observed is
 * [ContributorsSession]'s call, the same split [com.calypsan.listenup.web.features.library.LibraryPage]
 * makes between rendering sort state and owning it.
 *
 * [state] is `null` until the session has actually answered. An empty list and an unanswered query
 * are different facts, and saying the wrong one — "No authors yet." before the database has had a
 * chance to say otherwise — is worse than saying nothing, the same distinction
 * [com.calypsan.listenup.web.features.library.LibraryPage] draws between `Loading` and a `Loaded`
 * state with zero books. The header and facet row still render while `null`: they are
 * navigation, not data, so there is nothing about them to wait for.
 *
 * [letters] are the shared Library letter sections over [state] (`authorLetters` or `narratorLetters`),
 * so a person files under the same letter here as on Android and iOS; empty draws no headings.
 *
 * ⛔ No hours in the "N books" line. The artboard's row reads "6 books · 58h", but
 * [ContributorWithBookCount] carries no duration — inventing one here would be a number this page
 * made up. It stays books-only until a summed projection exists.
 */
@Composable
fun ContributorsPage(
    state: List<ContributorWithBookCount>?,
    role: ContributorRole,
    onSelectFacet: (LibraryFacet) -> Unit,
    onOpenContributor: (String) -> Unit,
    sortState: SortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
    onEvent: (LibraryUiEvent) -> Unit = {},
    letters: List<LetterSection> = emptyList(),
) {
    Div(attrs = { classes("lib-header") }) {
        PageHeader(title = "Contributors") {
            // Withheld rather than shown as "0" while state is null — a count is a fact about the
            // answer, and there isn't one yet.
            state?.let { list -> Span(attrs = { classes("contrib-count") }) { Text(list.size.toString()) } }
            // Sorting stays with an answered list, the same rule the Library's own header follows:
            // offering to reorder nothing is an affordance whose only outcome is nothing.
            if (state != null) ContributorSortControl(sortState, role, onEvent)
        }
    }
    // Books is never the active chip here — this page only ever renders for the Authors or
    // Narrators facet — but selecting it must still be able to navigate back to the library, so
    // the row carries all three the same way Library's own row does.
    FacetRow(
        active = if (role == ContributorRole.NARRATOR) LibraryFacet.Narrators else LibraryFacet.Authors,
        onSelect = onSelectFacet,
    )

    if (state == null) {
        LoadingState()
        return
    }

    if (state.isEmpty()) {
        EmptyContributors(role)
        return
    }

    // The letter headings are the shared [letters] — the sections every platform files a person
    // under. ⛔ They belong to a NAME sort and nothing else, and the shared state leaves them empty
    // otherwise: under "Most books" the list runs 47, 31, 12, and letter squares over that would
    // label runs of people with letters that mean nothing. The people stay in the ViewModel's order.
    val letterById =
        remember(state, letters) {
            buildMap {
                letters.forEach { section ->
                    for (index in section.firstIndex until minOf(section.firstIndex + section.count, state.size)) {
                        put(state[index].contributor.idString, section.letter.first())
                    }
                }
            }
        }
    // Windowed: a real library has thousands of narrators. Rows are one line each (the name is
    // clamped), so every row and every letter heading is one height and the list can be counted.
    VirtualList(
        items = state,
        key = { it.contributor.idString },
        containerClass = "contrib-list",
        itemSelector = ".contrib-row",
        label = if (role == ContributorRole.NARRATOR) "Narrators" else "Authors",
        sectionOf = { letterById[it.contributor.idString] },
        headerSelector = ".contrib-letter-row",
        header = { letter -> LetterHeading(letter) },
    ) { entry ->
        ContributorRow(
            entry = entry,
            role = role,
            onOpen = { onOpenContributor(entry.contributor.idString) },
        )
    }
}

/**
 * Sort category and direction for whichever contributor list is showing.
 *
 * The events are per-role because the ViewModel keeps a separate sort for each: a reader who sorts
 * Narrators by book count has not asked for their Authors to change. Both ride [LibraryUiEvent], so
 * the shared ViewModel owns persistence and the choice follows them to their phone — the browser
 * never stores a sort preference of its own.
 */
@Composable
private fun ContributorSortControl(
    sortState: SortState,
    role: ContributorRole,
    onEvent: (LibraryUiEvent) -> Unit,
) {
    val isNarrator = role == ContributorRole.NARRATOR
    SortDropdown(
        noun = if (isNarrator) "narrators" else "authors",
        categories = CONTRIBUTOR_SORT_CATEGORIES,
        state = sortState,
        // A person's name never loses its article, so there is nothing to ignore.
        ignoreArticles = null,
        onToggleIgnoreArticles = {},
        onCategory = { category ->
            onEvent(
                if (isNarrator) {
                    LibraryUiEvent.NarratorsCategoryChanged(category)
                } else {
                    LibraryUiEvent.AuthorsCategoryChanged(category)
                },
            )
        },
        onToggleDirection = {
            onEvent(
                if (isNarrator) LibraryUiEvent.NarratorsDirectionToggled else LibraryUiEvent.AuthorsDirectionToggled,
            )
        },
    )
}

/**
 * Categories a contributor list sorts by — the two iOS's `ContributorListContent` offers.
 *
 * An explicit list rather than `SortCategory.entries` for the reason the Books and Series tabs keep
 * one: the enum carries categories that only mean something for a book.
 */
private val CONTRIBUTOR_SORT_CATEGORIES = listOf(SortCategory.NAME, SortCategory.BOOK_COUNT)

@Composable
private fun LetterHeading(letter: Char) {
    Div(attrs = { classes("contrib-letter-row") }) {
        Span(attrs = { classes("contrib-letter") }) { Text(letter.toString()) }
        Div(attrs = { classes("contrib-letter-line") })
    }
}

@Composable
private fun ContributorRow(
    entry: ContributorWithBookCount,
    role: ContributorRole,
    onOpen: () -> Unit,
) {
    Div(attrs = {
        classes("contrib-row")
        tabIndex(0)
        attr("role", "button")
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                openWithOrigin(entry.contributor.idString, event.currentTarget as? Element, onOpen)
            }
        }
        onClick { event -> openWithOrigin(entry.contributor.idString, event.currentTarget as? Element, onOpen) }
    }) {
        Div(attrs = {
            classes("contrib-avatar")
            // Decorative: the row's accessible name should read "Andy Weir, Author, 6 books", not
            // lead with the two-letter monogram.
            attr("aria-hidden", "true")
            style { property("background", avatarTintFor(entry.contributor.name)) }
            // The return leg: Back from this person's page flies their avatar home here.
            heroTile(contributorHeroKey(entry.contributor.idString))
        }) {
            ContributorPhoto(
                contributorId = entry.contributor.idString,
                name = entry.contributor.name,
                imagePath = entry.contributor.imagePath,
                frame = entry.contributor.imageFrame,
            )
        }

        Div(attrs = { classes("contrib-info") }) {
            Div(attrs = { classes("contrib-name") }) { Text(entry.contributor.name) }
            Div(attrs = { classes("contrib-meta") }) {
                Span(attrs = {
                    classes("contrib-role-chip")
                    if (role == ContributorRole.NARRATOR) classes("is-narrator")
                }) { Text(roleLabel(role)) }
                Span(attrs = { classes("contrib-book-count") }) { Text(bookCountLabel(entry.bookCount)) }
            }
        }

        Div(attrs = { classes("contrib-chevron") }) {
            Icon(WebIcon.ChevronRight, size = CHEVRON_SIZE)
        }
    }
}

/**
 * Opens [contributorId], first recording where the row's avatar is so their page's hero can fly in
 * from it. Shared by pointer and keyboard so the flight is not a mouse-only courtesy — the same
 * shape the library card's `openWithOrigin` takes.
 */
private fun openWithOrigin(
    contributorId: String,
    row: Element?,
    onOpen: () -> Unit,
) {
    row?.querySelector(".contrib-avatar")?.let {
        recordHeroOrigin(contributorHeroKey(contributorId), CoverSurface.GRID, it)
    }
    onOpen()
}

/**
 * Zero contributors for a role is never rendered as a blank box — it says which role came up
 * empty, in the reader's own words, rather than leaving the page looking broken.
 */
@Composable
private fun EmptyContributors(role: ContributorRole) {
    EmptyState(title = if (role == ContributorRole.NARRATOR) "No narrators yet." else "No authors yet.")
}

private fun roleLabel(role: ContributorRole): String = if (role == ContributorRole.NARRATOR) "Narrator" else "Author"

/** "1 book" vs "6 books" — the artboard only ever shows the plural, but a one-book credit is real. */
private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

private const val CHEVRON_SIZE = 20
