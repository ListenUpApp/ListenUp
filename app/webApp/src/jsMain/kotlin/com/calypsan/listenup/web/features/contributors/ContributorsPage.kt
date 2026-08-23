package com.calypsan.listenup.web.features.contributors

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.util.nameLetter
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.abs

/**
 * The Contributors list — every author and narrator in the library, A to Z.
 *
 * Renders [state] and nothing else: which role is showing, and the count in the header pill, are
 * both facts the caller already knows rather than something this page infers. The toggle here only
 * reports the gesture ([onSelectRole]) — which flow is actually being observed is
 * [ContributorsSession]'s call, the same split [com.calypsan.listenup.web.features.library.LibraryPage]
 * makes between rendering sort state and owning it.
 *
 * ⛔ No hours in the "N books" line. The artboard's row reads "6 books · 58h", but
 * [ContributorWithBookCount] carries no duration — inventing one here would be a number this page
 * made up. It stays books-only until a summed projection exists.
 */
@Composable
fun ContributorsPage(
    state: List<ContributorWithBookCount>,
    role: String,
    onSelectRole: (String) -> Unit,
    onOpenContributor: (String) -> Unit,
) {
    Div(attrs = { classes("contrib-header") }) {
        Div(attrs = { classes("contrib-title-row") }) {
            H3 { Text("Contributors") }
            Span(attrs = { classes("contrib-count") }) { Text(state.size.toString()) }
        }
        RoleToggle(role, onSelectRole)
    }

    if (state.isEmpty()) {
        EmptyContributors(role)
        return
    }

    Div(attrs = { classes("contrib-list") }) {
        groupByLetter(state).forEach { group ->
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
private fun RoleToggle(
    role: String,
    onSelectRole: (String) -> Unit,
) {
    Div(attrs = { classes("contrib-toggle") }) {
        RoleChip(
            label = "Authors",
            forRole = ContributorRole.AUTHOR.apiValue,
            activeRole = role,
            onSelectRole = onSelectRole,
        )
        RoleChip(
            label = "Narrators",
            forRole = ContributorRole.NARRATOR.apiValue,
            activeRole = role,
            onSelectRole = onSelectRole,
        )
    }
}

@Composable
private fun RoleChip(
    label: String,
    forRole: String,
    activeRole: String,
    onSelectRole: (String) -> Unit,
) {
    Span(attrs = {
        classes("contrib-toggle-chip")
        if (forRole == activeRole) classes("is-active")
        attr("role", "button")
        tabIndex(0)
        onClick { onSelectRole(forRole) }
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                onSelectRole(forRole)
            }
        }
    }) { Text(label) }
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
    role: String,
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
            style { property("background", avatarTintFor(entry.contributor.name)) }
        }) { Text(initialsFor(entry.contributor.name)) }

        Div(attrs = { classes("contrib-info") }) {
            Div(attrs = { classes("contrib-name") }) { Text(entry.contributor.name) }
            Div(attrs = { classes("contrib-meta") }) {
                Span(attrs = {
                    classes("contrib-role-chip")
                    if (role == ContributorRole.NARRATOR.apiValue) classes("is-narrator")
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
private fun EmptyContributors(role: String) {
    Div(attrs = { classes("empty") }) {
        H3 { Text(if (role == ContributorRole.NARRATOR.apiValue) "No narrators yet." else "No authors yet.") }
    }
}

private fun roleLabel(role: String): String = if (role == ContributorRole.NARRATOR.apiValue) "Narrator" else "Author"

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
 * The same trick [com.calypsan.listenup.web.design.Cover]'s `gradientFor` uses for a book with no
 * artwork — hash the string, derive a hue, offset a second stop off it — tuned darker and more
 * saturated so two-letter initials stay legible at 58px against it.
 */
private fun avatarTintFor(name: String): String {
    val hue = abs(name.hashCode()) % HUE_RANGE
    val second = (hue + HUE_SPREAD) % HUE_RANGE
    return "linear-gradient(150deg, hsl($hue 42% 20%), hsl($second 46% 8%))"
}

private val WHITESPACE = Regex("\\s+")

private const val HUE_RANGE = 360

private const val HUE_SPREAD = 24

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
 */
fun groupByLetter(contributors: List<ContributorWithBookCount>): List<LetterGroup> =
    contributors
        .sortedBy { it.contributor.name.lowercase() }
        .groupBy { it.contributor.name.nameLetter() }
        .entries
        .sortedBy { (letter, _) -> if (letter == HASH_LETTER) Int.MIN_VALUE else letter.code }
        .map { (letter, group) -> LetterGroup(letter, group) }

private const val HASH_LETTER = '#'
