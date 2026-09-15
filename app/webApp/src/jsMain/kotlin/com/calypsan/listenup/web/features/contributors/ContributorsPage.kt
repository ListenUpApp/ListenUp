package com.calypsan.listenup.web.features.contributors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.client.util.nameLetter
import com.calypsan.listenup.web.design.FacetRow
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.avatarTintFor
import com.calypsan.listenup.web.design.initialsFor
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

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
) {
    Div(attrs = { classes("contrib-header") }) {
        Div(attrs = { classes("contrib-title-row") }) {
            H3 { Text("Contributors") }
            // Withheld rather than shown as "0" while state is null — a count is a fact about the
            // answer, and there isn't one yet.
            state?.let { list -> Span(attrs = { classes("contrib-count") }) { Text(list.size.toString()) } }
        }
        // Sorting stays with an answered list, the same rule the Library's own header follows:
        // offering to reorder nothing is an affordance whose only outcome is nothing.
        if (state != null) ContributorSortControl(sortState, role, onEvent)
    }
    // Books is never the active chip here — this page only ever renders for the Authors or
    // Narrators facet — but selecting it must still be able to navigate back to the library, so
    // the row carries all three the same way Library's own row does.
    FacetRow(
        active = if (role == ContributorRole.NARRATOR) LibraryFacet.Narrators else LibraryFacet.Authors,
        onSelect = onSelectFacet,
    )

    if (state == null) {
        Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
        return
    }

    if (state.isEmpty()) {
        EmptyContributors(role)
        return
    }

    Div(attrs = { classes("contrib-list") }) {
        // ⛔ The letter rail belongs to a NAME sort and nothing else. Under "Most books" the list
        // runs 47, 31, 12 — letter squares over that would label runs of people with letters that
        // mean nothing, which is the same call the Books tab makes for its Added and Duration sorts.
        if (sortState.category == SortCategory.NAME) {
            // Re-grouped on every recomposition otherwise; keyed on the list itself, the same
            // precedent `VirtualBookGrid` sets for its own `layOut(...)` call.
            val groups = remember(state) { groupByLetter(state) }
            groups.forEach { group ->
                Div(attrs = { classes("contrib-section") }) {
                    LetterHeading(group.letter)
                    group.contributors.forEach { entry ->
                        ContributorRow(
                            entry = entry,
                            role = role,
                            onOpen = { onOpenContributor(entry.contributor.idString) },
                        )
                    }
                }
            }
        } else {
            state.forEach { entry ->
                ContributorRow(
                    entry = entry,
                    role = role,
                    onOpen = { onOpenContributor(entry.contributor.idString) },
                )
            }
        }
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
    Div(attrs = { classes("lib-sort") }) {
        CONTRIBUTOR_SORT_CATEGORIES.forEach { category ->
            Div(attrs = {
                classes("lib-sort-option")
                if (sortState.category == category) classes("is-active")
                onClick {
                    onEvent(
                        if (isNarrator) {
                            LibraryUiEvent.NarratorsCategoryChanged(category)
                        } else {
                            LibraryUiEvent.AuthorsCategoryChanged(category)
                        },
                    )
                }
            }) { Text(category.label) }
        }
        Div(attrs = {
            classes("lib-sort-direction")
            onClick {
                onEvent(
                    if (isNarrator) LibraryUiEvent.NarratorsDirectionToggled else LibraryUiEvent.AuthorsDirectionToggled,
                )
            }
        }) { Text(if (sortState.direction == SortDirection.ASCENDING) "↑" else "↓") }
    }
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
                onOpen()
            }
        }
        onClick { onOpen() }
    }) {
        Div(attrs = {
            classes("contrib-avatar")
            // Decorative: the row's accessible name should read "Andy Weir, Author, 6 books", not
            // lead with the two-letter monogram.
            attr("aria-hidden", "true")
            style { property("background", avatarTintFor(entry.contributor.name)) }
        }) { Text(initialsFor(entry.contributor.name)) }

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
 * Zero contributors for a role is never rendered as a blank box — it says which role came up
 * empty, in the reader's own words, rather than leaving the page looking broken.
 */
@Composable
private fun EmptyContributors(role: ContributorRole) {
    Div(attrs = { classes("empty") }) {
        H3 { Text(if (role == ContributorRole.NARRATOR) "No narrators yet." else "No authors yet.") }
    }
}

private fun roleLabel(role: ContributorRole): String = if (role == ContributorRole.NARRATOR) "Narrator" else "Author"

/** "1 book" vs "6 books" — the artboard only ever shows the plural, but a one-book credit is real. */
private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

private const val CHEVRON_SIZE = 20

/** One alphabetical section of the Contributors list: its letter square, and who files under it. */
data class LetterGroup(
    val letter: Char,
    val contributors: List<ContributorWithBookCount>,
)

/**
 * Splits [contributors] into A→Z sections for the letter-square rail this list renders under.
 *
 * The letter itself is delegated to the shared [nameLetter] — the same rule Android, iOS and
 * Library's own author/narrator rail use for a *person's* name — rather than reinterpreted here.
 * A person's name is never article-stripped ("The Rolling Stones" files under T), so every
 * platform files a given contributor under the same letter. Names with no leading letter (blank,
 * numeric, symbolic) group under `#`, sorted first — [nameLetter]'s own contract.
 *
 * ⛔ Groups and nothing else — it neither sorts the people nor orders the sections.
 *
 * Both used to happen here: a `sortedBy { name.lowercase() }` because the raw repository result came
 * back in SQLite's case-sensitive BINARY collation ("Zoe" before "andy"), and a `sortedBy` on the
 * letters to run the rail A→Z. The list now arrives already ordered by
 * `LibraryViewModel.sortContributors`, and **ordering the sections here contradicted it**: under a
 * descending name sort the ViewModel hands back Z→A and this re-ran the rail A→Z, so pressing ↓
 * reversed the people inside each letter and left the letters themselves untouched.
 *
 * `groupBy` preserves encounter order, so the sections now come out in whatever order the caller's
 * sort put them — which is the reader's answer, not a second opinion. `#` lands where the sort puts
 * it rather than being pinned first.
 */
fun groupByLetter(contributors: List<ContributorWithBookCount>): List<LetterGroup> =
    contributors
        .groupBy { it.contributor.name.nameLetter() }
        .map { (letter, group) -> LetterGroup(letter, group) }
