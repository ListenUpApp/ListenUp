package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.client.presentation.match.BiographyUi
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.client.presentation.match.PhotoUi
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Dl
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.features.contributoredit.contributorPhotoUrl
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Fieldset
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Legend
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.RadioInput
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Person Review (W-06, W-07): the person's header, then the photo and the biography — each its own decision,
 * under its own heading — and the Apply bar pinned to the bottom of the pane.
 *
 * The photo is a checkbox (Change photo) and one radio group: Keep current, shown with the real current photo,
 * and one tile per source. The biography is a checkbox (Apply biography), a source switch with Keep yours when
 * there is something to keep, and Yours → Proposed with Read all. Skip to Apply follows the heading.
 */
@Suppress("LongParameterList")
@Composable
internal fun PersonReviewPane(
    review: PersonReviewUiState,
    personName: String,
    contributorId: String,
    viewerId: String?,
    session: PersonMatchSession,
    reloaded: Boolean,
    onBack: (PersonCandidateUi) -> Unit,
    onRetry: (PersonCandidateUi) -> Unit,
) {
    H2(attrs = {
        classes("bmx-pane-t")
        attr("id", REVIEW_HEADING_ID)
        attr("tabindex", "-1")
    }) { Text("Review") }

    when (review) {
        PersonReviewUiState.NoneChosen -> {
            EmptyState(title = "Pick a person to review them.", look = EmptyLook.Inline)
        }

        is PersonReviewUiState.Loading -> {
            PersonReviewHeader(review.candidate, onBack)
            Div(attrs = {
                classes("skel", "bmx-skel")
                attr("aria-hidden", "true")
            })
            P(attrs = { classes(NOTE) }) { Text(LOADING_MATCH) }
        }

        is PersonReviewUiState.Failed -> {
            PersonReviewHeader(review.candidate, onBack)
            EmptyState(
                title = "Couldn't load this match",
                body = review.error.message,
                look = EmptyLook.Inset,
                action = {
                    Div(attrs = { classes("bmx-fail-acts") }) {
                        Button(kind = ButtonKind.Secondary, onClick = { onRetry(review.candidate) }) { Text("Retry") }
                    }
                },
            )
        }

        is PersonReviewUiState.Ready -> {
            SkipToApply()
            PersonReviewHeader(review.candidate, onBack)
            if (reloaded) P(attrs = { classes("bmx-err") }) { Text(REVIEW_RELOADED_PERSON) }
            P(attrs = { classes(NOTE, "pmx-apart") }) { Text("Photo and biography, chosen separately.") }
            review.photo?.let { PhotoSection(it, personName, contributorId, session) }
            review.biography?.let { BiographySection(it, viewerId, session) }
            ApplyBar(
                summary = personApplyBarText(review.applyBar),
                note = NOTHING_CHANGES_UNTIL_APPLY,
                canApply = review.applyBar.canApply,
                applying = review.applying,
                applyError = review.applyError,
                onApply = session.apply,
            )
        }
    }
}

/** The person being reviewed: photo, Best match, name, "Narrator · from Hardcover", their books here. */
@Composable
private fun PersonReviewHeader(
    candidate: PersonCandidateUi,
    onBack: (PersonCandidateUi) -> Unit,
) {
    Div(attrs = { classes("bmx-head") }) {
        Portrait(url = candidate.photoUrl, name = candidate.name, big = true)
        Div(attrs = { classes("bmx-head-m") }) {
            RowBadges(isBest = candidate.isBest, isCurrentLink = candidate.isCurrentLink)
            Span(attrs = { classes("bmx-head-t") }) { Text(candidate.name) }
            Span(attrs = { classes("bmx-row-meta", "pmx-from") }) { Text(personHeaderFromText(candidate)) }
            LibraryLine(candidate)
        }
        Button(kind = ButtonKind.Ghost, onClick = { onBack(candidate) }) { Text("Back to results") }
    }
}

/**
 * The section heading row: the tick that says whether Apply writes this part, the heading, and what kind of
 * change it is. [tick] is null when there is nothing to choose.
 */
@Composable
private fun PartHeading(
    id: String,
    title: String,
    tick: (@Composable () -> Unit)?,
    state: FieldState,
) {
    Div(attrs = { classes("bmx-field-h") }) {
        tick?.let { Label(attrs = { classes("f-check") }) { it() } }
        H3(attrs = {
            classes("bmx-sec-t")
            attr("id", id)
            attr("tabindex", "-1")
        }) { Text(title) }
        when (state) {
            FieldState.USER_EDITED -> Span(attrs = { classes("bmx-edited") }) { Text("You edited this") }
            FieldState.FILLS_GAP -> Span(attrs = { classes(NOTE) }) { Text("fills a gap") }
            FieldState.CHANGES -> Span(attrs = { classes(NOTE) }) { Text("chosen on its own") }
            FieldState.SAME -> Unit
        }
    }
}

/** The photo: Change photo, and one radio group — Keep current first, then each source's. */
@Composable
private fun PhotoSection(
    photo: PhotoUi,
    personName: String,
    contributorId: String,
    session: PersonMatchSession,
) {
    Section(attrs = {
        classes("bmx-sec", "pmx-photo")
        attr("aria-labelledby", SECTION_PHOTO)
    }) {
        PartHeading(
            id = SECTION_PHOTO,
            title = "Photo",
            tick = {
                CheckboxInput(checked = photo.isTicked) {
                    attr(ATTR_ARIA_LABEL, "Change photo")
                    onChange { event -> session.setPhotoTicked(event.value) }
                }
            },
            state = photo.state,
        )
        if (photo.setByHand) {
            P(attrs = { classes("bmx-edited-note") }) {
                Text("You set this photo by hand. Kept unless you choose another.")
            }
        }
        Fieldset(attrs = { classes("bmx-fieldset", "pmx-photos") }) {
            Legend(attrs = { classes("sr-only") }) { Text("Photo") }
            Div(attrs = { classes("bmx-covers") }) {
                PhotoTile(
                    checked = photo.choice == ImageChoice.KeepCurrent,
                    face = {
                        Portrait(
                            url = photo.currentPath?.let { contributorPhotoUrl(contributorId) },
                            name = personName,
                            big = true,
                        )
                    },
                    label = "Keep current",
                    detail = if (photo.currentPath != null) "Your photo" else "No photo yet",
                    name = "Keep current photo",
                    onChoose = { session.choosePhoto(ImageChoice.KeepCurrent) },
                )
                photo.options.forEach { option ->
                    PhotoTile(
                        checked = (photo.choice as? ImageChoice.Candidate)?.optionId == option.optionId,
                        face = { Portrait(url = option.url, name = personName, big = true) },
                        label = option.source.label,
                        detail = "Proposed",
                        name = "Photo from ${option.source.label}",
                        onChoose = { session.choosePhoto(ImageChoice.Candidate(option.optionId)) },
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun PhotoTile(
    checked: Boolean,
    face: @Composable () -> Unit,
    label: String,
    detail: String,
    name: String,
    onChoose: () -> Unit,
) {
    Label(attrs = { classes("bmx-cover") }) {
        face()
        RadioInput(checked = checked) {
            attr("name", PHOTO_GROUP)
            attr(ATTR_ARIA_LABEL, name)
            onChange { if (it.value) onChoose() }
        }
        Span(attrs = { classes("bmx-cover-l") }) { Text(label) }
        Span(attrs = { classes("bmx-dim") }) { Text(detail) }
    }
}

/**
 * The biography: Apply biography, the source switch, Yours → Proposed with Read all, and — for one you edited —
 * who edited it. One that already matches says so and offers nothing to tick.
 */
@Composable
private fun BiographySection(
    biography: BiographyUi,
    viewerId: String?,
    session: PersonMatchSession,
) {
    val same = biography.state == FieldState.SAME
    Section(attrs = {
        classes("bmx-sec", "pmx-bio")
        attr("aria-labelledby", SECTION_BIOGRAPHY)
    }) {
        PartHeading(
            id = SECTION_BIOGRAPHY,
            title = "Biography",
            tick =
                if (same) {
                    null
                } else {
                    {
                        CheckboxInput(checked = biography.isTicked) {
                            attr(ATTR_ARIA_LABEL, "Apply biography")
                            onChange { event -> session.setBiographyTicked(event.value) }
                        }
                    }
                },
            state = biography.state,
        )
        if (same) {
            P(attrs = { classes(NOTE) }) { Text("Your biography already matches.") }
            return@Section
        }
        if (biography.options.size > 1 || biography.canKeepYours) BiographySourceSwitch(biography, session)
        val from = sourcesText(biography.proposed.sources)
        Dl(attrs = { classes("bmx-vals") }) {
            Value("Yours", biography.current?.let(::plainText)?.ifBlank { null } ?: "—", long = true)
            Value("Proposed · from $from", valueText(biography.proposed.value), long = true)
        }
        if (biography.state == FieldState.USER_EDITED) {
            P(attrs = { classes("bmx-edited-note") }) { Text(editedByText(biography.handEdit, viewerId)) }
        }
    }
}

/** "Audible | Hardcover | Keep yours" as one radio group — the checkbox and the switch are one value. */
@Composable
private fun BiographySourceSwitch(
    biography: BiographyUi,
    session: PersonMatchSession,
) {
    Fieldset(attrs = { classes("bmx-fieldset", "pmx-bio-src") }) {
        Legend(attrs = { classes("sr-only") }) { Text("Biography source") }
        Div(attrs = { classes("bmx-src") }) {
            biography.options.forEach { option ->
                Segment(
                    group = BIOGRAPHY_GROUP,
                    checked = (biography.choice as? FieldChoice.Option)?.optionId == option.optionId,
                    text = sourcesText(option.sources),
                    onChoose = { session.chooseBiographySource(FieldChoice.Option(option.optionId)) },
                )
            }
            if (biography.canKeepYours) {
                Segment(
                    group = BIOGRAPHY_GROUP,
                    checked = biography.choice == FieldChoice.KeepCurrent,
                    text = "Keep yours",
                    onChoose = { session.chooseBiographySource(FieldChoice.KeepCurrent) },
                )
            }
        }
    }
}

internal const val SECTION_PHOTO = "pmx-sec-photo"
internal const val SECTION_BIOGRAPHY = "pmx-sec-bio"
private const val PHOTO_GROUP = "pmx-photo"
private const val BIOGRAPHY_GROUP = "pmx-bio-src"
private const val NOTE = "bmx-note"
private const val ATTR_ARIA_LABEL = "aria-label"
