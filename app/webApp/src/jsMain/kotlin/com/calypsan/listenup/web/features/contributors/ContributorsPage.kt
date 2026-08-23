package com.calypsan.listenup.web.features.contributors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.util.nameLetter
import com.calypsan.listenup.web.design.FacetRow
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.tintGradient
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
) {
    Div(attrs = { classes("contrib-header") }) {
        Div(attrs = { classes("contrib-title-row") }) {
            H3 { Text("Contributors") }
            // Withheld rather than shown as "0" while state is null — a count is a fact about the
            // answer, and there isn't one yet.
            state?.let { list -> Span(attrs = { classes("contrib-count") }) { Text(list.size.toString()) } }
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
        Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
        return
    }

    if (state.isEmpty()) {
        EmptyContributors(role)
        return
    }

    Div(attrs = { classes("contrib-list") }) {
        // `state` is re-sorted and re-grouped on every recomposition otherwise; keyed on the list
        // itself, the same precedent `VirtualBookGrid` sets for its own `layOut(...)` call.
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
    }
}

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

/** Up to two initials: the first letter of the first and last name tokens, or just one for a single-word name. */
private fun initialsFor(name: String): String {
    val parts = name.trim().split(WHITESPACE).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

/**
 * A stable, name-derived tint for a contributor's avatar.
 *
 * Shares [tintGradient] with [com.calypsan.listenup.web.design.Cover]'s fallback for a book with
 * no artwork — the same hash-the-string-into-a-hue trick, tuned darker and more saturated so
 * two-letter initials stay legible at 58px against it.
 */
private fun avatarTintFor(name: String): String =
    tintGradient(
        seed = name,
        angleDegrees = AVATAR_GRADIENT_ANGLE,
        firstSaturation = AVATAR_FIRST_SATURATION,
        firstLightness = AVATAR_FIRST_LIGHTNESS,
        secondSaturation = AVATAR_SECOND_SATURATION,
        secondLightness = AVATAR_SECOND_LIGHTNESS,
    )

private val WHITESPACE = Regex("\\s+")

private const val AVATAR_GRADIENT_ANGLE = 150

private const val AVATAR_FIRST_SATURATION = 42

private const val AVATAR_FIRST_LIGHTNESS = 20

private const val AVATAR_SECOND_SATURATION = 46

private const val AVATAR_SECOND_LIGHTNESS = 8

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
 * Sorted with `lowercase()` rather than relying on the caller's own order: SQLite's default
 * BINARY collation is case-sensitive, so a repository result ordered by that collation would put
 * "Zoe" before "andy" — this sort is what actually puts both under the right letter in A→Z order.
 */
fun groupByLetter(contributors: List<ContributorWithBookCount>): List<LetterGroup> =
    contributors
        .sortedBy { it.contributor.name.lowercase() }
        .groupBy { it.contributor.name.nameLetter() }
        .entries
        .sortedBy { (letter, _) -> if (letter == HASH_LETTER) Int.MIN_VALUE else letter.code }
        .map { (letter, group) -> LetterGroup(letter, group) }

private const val HASH_LETTER = '#'
