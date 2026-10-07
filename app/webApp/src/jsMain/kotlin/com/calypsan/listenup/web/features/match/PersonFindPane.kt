package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.client.presentation.match.CoverageNote
import com.calypsan.listenup.client.presentation.match.InLibraryUi
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.focusLanding
import com.calypsan.listenup.web.design.initialsFor
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * Person Find (W-06, W-07): the coverage note, Your library, the search, the steps Find took, and the people in
 * Strong match and Maybe — or that no source has a profile for this role, with Edit by hand, or why Find failed.
 *
 * A search in flight keeps the role's last results on screen, marked busy. Each row is one `<button
 * aria-pressed>` whose accessible name says who the person is; [onPick] moves the focus.
 */
@Composable
internal fun PersonFindPane(
    find: PersonFindUiState,
    session: PersonMatchSession,
    onPick: (PersonCandidateUi) -> Unit,
    onEditByHand: () -> Unit,
) {
    val shown: PersonFindUiState.Results? =
        when (find) {
            is PersonFindUiState.Results -> find
            is PersonFindUiState.Searching -> find.previous
            is PersonFindUiState.NoProfiles, is PersonFindUiState.Failed -> null
        }
    val coverage: CoverageNote? = if (find is PersonFindUiState.NoProfiles) find.coverageNote else shown?.coverageNote
    val name = find.header?.name.orEmpty()

    Div(attrs = { classes("bmx-pane-h") }) {
        H2(attrs = {
            classes("bmx-pane-t")
            attr("id", FIND_HEADING_ID)
            attr("tabindex", "-1")
        }) { Text("Find") }
        shown?.let { Span(attrs = { classes("bmx-count") }) { Text(peopleCountText(it.all.size)) } }
    }

    coverage?.let { CoverageNoteLine(it, find.role) }
    find.inLibrary?.let { InLibraryStrip(it) }
    PersonSearchForm(query = find.query, role = find.role, onSearch = session.search)

    if (find is PersonFindUiState.Searching) {
        P(attrs = {
            classes("bmx-searching")
            attr("aria-hidden", "true")
        }) {
            Icon(WebIcon.Clock, size = SMALL_ICON)
            Text(SEARCHING)
        }
        if (shown == null) Div(attrs = { classes("skel", "bmx-skel") })
    }

    shown?.let { personStepsText(it.steps, name, find.role) }?.let { P(attrs = { classes("bmx-note") }) { Text(it) } }
    shown?.partialFailure?.let { PartialBanner(it, session.retry) }
    shown?.let { results ->
        val busy = find is PersonFindUiState.Searching
        PersonGroup("Strong match", results.strong, results, busy, onPick)
        PersonGroup("Maybe", results.maybe, results, busy, onPick)
    }

    when (find) {
        is PersonFindUiState.NoProfiles -> {
            EmptyState(
                title = noProfilesTitle(find.role),
                body = NO_PROFILES_BODY,
                look = EmptyLook.Inset,
                action = {
                    Div(attrs = { classes("bmx-fail-acts") }) {
                        Button(kind = ButtonKind.Secondary, onClick = onEditByHand) { Text(EDIT_BY_HAND) }
                    }
                },
            )
        }

        is PersonFindUiState.Failed -> {
            FailureCard(failure = find.failure, onRetry = session.retry)
        }

        else -> {
            Unit
        }
    }
}

/** "Audible has no narrator profiles, so this search uses Hardcover." */
@Composable
private fun CoverageNoteLine(
    note: CoverageNote,
    role: ContributorRole,
) {
    P(attrs = { classes("pmx-coverage") }) {
        Icon(WebIcon.Info, size = SMALL_ICON)
        Text(coverageNoteText(note, role))
    }
}

/** Your library: up to three covers and "Wrote 3 books in your library: …", read from this device. */
@Composable
private fun InLibraryStrip(inLibrary: InLibraryUi) {
    Div(attrs = { classes("bmx-copy", "pmx-lib") }) {
        if (inLibrary.covers.isNotEmpty()) {
            Span(attrs = {
                classes("pmx-lib-covers")
                attr("aria-hidden", "true")
            }) {
                inLibrary.covers.forEach { cover ->
                    val hasCover = cover.coverPath != null || cover.coverHash != null
                    Art(url = if (hasCover) coverUrl(cover.bookId, cover.coverHash, width = LIBRARY_ART_WIDTH) else null)
                }
            }
        }
        Div(attrs = { classes("bmx-copy-text") }) {
            Span(attrs = { classes("bmx-copy-l") }) { Text("In your library") }
            Span(attrs = { classes("bmx-copy-m", "pmx-lib-t") }) { Text(inLibraryText(inLibrary)) }
        }
    }
}

/** The search: a labelled field in a real search form, so Enter searches; never per keystroke. */
@Composable
private fun PersonSearchForm(
    query: String,
    role: ContributorRole,
    onSearch: (String) -> Unit,
) {
    var text by remember(query) { mutableStateOf(query) }
    Form(attrs = {
        classes("bmx-search")
        attr("role", "search")
        onSubmit { event ->
            event.preventDefault()
            onSearch(text)
        }
    }) {
        Div(attrs = {
            classes("bmx-q")
            focusLanding(priority = 2)
        }) {
            Field(
                label = personSearchLabel(role),
                value = text,
                onInput = { text = it },
                leading = WebIcon.Search,
                id = SEARCH_ID,
            )
        }
        Button(kind = ButtonKind.Secondary, submit = true) { Text("Search") }
    }
}

@Composable
private fun PersonGroup(
    title: String,
    people: List<PersonCandidateUi>,
    results: PersonFindUiState.Results,
    busy: Boolean,
    onPick: (PersonCandidateUi) -> Unit,
) {
    if (people.isEmpty()) return
    Div(attrs = { classes("bmx-group") }) {
        H3(attrs = { classes("bmx-group-t") }) {
            Text(title)
            Span(attrs = { classes("bmx-count") }) { Text(people.size.toString()) }
        }
        Ul(attrs = {
            classes("bmx-rows")
            if (busy) attr("aria-busy", "true")
        }) {
            people.forEach { person ->
                Li {
                    PersonRow(person, results.role, picked = person.key == results.pickedKey, onPick = onPick)
                }
            }
        }
    }
}

/** One person: their photo or initials, name, role and works, your library (or Different role), sources. */
@Composable
private fun PersonRow(
    person: PersonCandidateUi,
    searched: ContributorRole,
    picked: Boolean,
    onPick: (PersonCandidateUi) -> Unit,
) {
    DomButton(attrs = {
        classes("bmx-row", "pmx-row")
        attr("type", "button")
        attr("id", rowIdOf(person.id))
        attr("aria-pressed", picked.toString())
        attr("aria-label", personRowName(person, searched))
        onClick { onPick(person) }
    }) {
        Portrait(url = person.photoUrl, name = person.name)
        Span(attrs = { classes("bmx-row-m") }) {
            RowBadges(isBest = person.isBest, isCurrentLink = person.isCurrentLink)
            Span(attrs = { classes("bmx-row-t") }) { Text(person.name) }
            Span(attrs = { classes("bmx-row-meta") }) { Text(personMetaText(person, searched)) }
            if (person.isDifferentRole) {
                Span(attrs = { classes("bmx-badges") }) {
                    Span(attrs = { classes("bmx-badge", "pmx-role-chip") }) { Text(DIFFERENT_ROLE) }
                }
            } else {
                LibraryLine(person, searched)
            }
            if (person.foundIn.isNotEmpty()) {
                Span(attrs = { classes("bmx-row-found") }) { Text("Found in ${sourcesText(person.foundIn)}") }
            }
        }
    }
}

/** "Wrote 3 books in your library", with a check when there are some; "No books in your library" otherwise. */
@Composable
internal fun LibraryLine(
    person: PersonCandidateUi,
    searched: ContributorRole,
) {
    val some = !person.noBooksInLibrary && person.libraryCount > 0
    Span(attrs = {
        classes("pmx-lib-line")
        if (some) classes("is-some")
    }) {
        if (some) Icon(WebIcon.Check, size = SMALL_ICON)
        Text(personLibraryText(person, searched))
    }
}

/** A person's photo, or their initials where there is none. Decorative: the name is beside it. */
@Composable
internal fun Portrait(
    url: String?,
    name: String,
    big: Boolean = false,
) {
    if (url == null) {
        Span(attrs = {
            classes("pmx-face", "pmx-face-blank")
            if (big) classes("is-big")
            attr("aria-hidden", "true")
        }) { Text(initialsFor(name)) }
    } else {
        Img(src = url, alt = "", attrs = {
            classes("pmx-face")
            if (big) classes("is-big")
            attr("loading", "lazy")
            attr("referrerpolicy", "no-referrer")
        })
    }
}

private const val LIBRARY_ART_WIDTH = 112
