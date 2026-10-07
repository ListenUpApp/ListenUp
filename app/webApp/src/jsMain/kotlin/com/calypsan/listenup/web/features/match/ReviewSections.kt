package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.client.presentation.match.ChapterNamesUi
import com.calypsan.listenup.client.presentation.match.CoverUi
import com.calypsan.listenup.client.presentation.match.FieldUi
import com.calypsan.listenup.client.presentation.match.LabelKind
import com.calypsan.listenup.client.presentation.match.LabelSetUi
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.CheckboxInput
import com.calypsan.listenup.web.design.Dd
import com.calypsan.listenup.web.design.Details
import org.jetbrains.compose.web.dom.Div
import com.calypsan.listenup.web.design.Dl
import com.calypsan.listenup.web.design.Dt
import org.jetbrains.compose.web.dom.Fieldset
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Legend
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.RadioInput
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import com.calypsan.listenup.web.design.Summary
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import com.calypsan.listenup.web.design.Button as KitButton
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * Review's sections, in canvas order — Cover, Changes, Fills a gap, You edited this, Genres & moods,
 * Chapter names, Already the same — each under its own heading, and none rendered when it is empty.
 */
@Composable
internal fun ReviewSections(
    review: ReviewUiState.Ready,
    bookId: String,
    viewerId: String?,
    session: BookMatchSession,
) {
    if (review.cover.options.isNotEmpty()) CoverSection(review.cover, bookId, session)
    FieldSection(SECTION_CHANGES, "Changes", null, review.changes, viewerId, session)
    FieldSection(SECTION_GAPS, "Fills a gap", null, review.fillsGap, viewerId, session)
    FieldSection(SECTION_EDITED, "You edited this", "Left as you have it unless you tick it.", review.youEdited, viewerId, session)
    LabelsSection(review.genres, review.moods, session)
    ChapterNamesSection(review.chapterNames, session)
    AlreadySameSection(review.alreadySame, review.lengthAlreadySame)
}

@Composable
private fun ReviewSection(
    id: String,
    title: String,
    content: @Composable () -> Unit,
) {
    Section(attrs = {
        classes("bmx-sec")
        attr("aria-labelledby", id)
    }) {
        H3(attrs = {
            classes("bmx-sec-t")
            attr("id", id)
            attr("tabindex", "-1")
        }) { Text(title) }
        content()
    }
}

/** The cover as one radio group: Keep current (your real cover) and every candidate's. */
@Composable
private fun CoverSection(
    cover: CoverUi,
    bookId: String,
    session: BookMatchSession,
) {
    ReviewSection(SECTION_COVER, "Cover") {
        P(attrs = { classes("bmx-note") }) { Text("Pick one. Keep current is always an option.") }
        Fieldset(attrs = { classes("bmx-fieldset") }) {
            Legend(attrs = { classes("sr-only") }) { Text("Cover") }
            Div(attrs = { classes("bmx-covers") }) {
                CoverOption(
                    checked = cover.choice == ImageChoice.KeepCurrent,
                    imageUrl = cover.current?.hash?.let { coverUrl(bookId, it, width = COVER_WIDTH) },
                    label = "Keep current",
                    detail = "Your cover",
                    name = "Keep current cover",
                    onChoose = { session.chooseCover(ImageChoice.KeepCurrent) },
                )
                cover.options.forEach { option ->
                    val sized = option.width > 0 && option.height > 0
                    CoverOption(
                        checked = (cover.choice as? ImageChoice.Candidate)?.optionId == option.optionId,
                        imageUrl = option.url,
                        label = option.source.label,
                        detail = if (sized) "${option.width} × ${option.height}" else null,
                        name =
                            if (sized) {
                                "Cover from ${option.source.label}, ${option.width} by ${option.height}"
                            } else {
                                "Cover from ${option.source.label}"
                            },
                        onChoose = { session.chooseCover(ImageChoice.Candidate(option.optionId)) },
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun CoverOption(
    checked: Boolean,
    imageUrl: String?,
    label: String,
    detail: String?,
    name: String,
    onChoose: () -> Unit,
) {
    Label(attrs = { classes("bmx-cover") }) {
        Art(url = imageUrl, big = true)
        RadioInput(checked = checked) {
            attr("name", COVER_GROUP)
            attr("aria-label", name)
            onChange { if (it.value) onChoose() }
        }
        Span(attrs = { classes("bmx-cover-l") }) { Text(label) }
        detail?.let { Span(attrs = { classes("bmx-dim") }) { Text(it) } }
    }
}

@Suppress("LongParameterList")
@Composable
private fun FieldSection(
    id: String,
    title: String,
    lede: String?,
    fields: List<FieldUi>,
    viewerId: String?,
    session: BookMatchSession,
) {
    if (fields.isEmpty()) return
    ReviewSection(id, title) {
        lede?.let { P(attrs = { classes("bmx-note") }) { Text(it) } }
        fields.forEach { FieldRow(it, viewerId, session) }
    }
}

/**
 * One field: a checkbox (ticked = Apply writes it), a source switch when there is more than one way
 * to fill it, Yours and Proposed, and — for a hand edit — who made it and when. The checkbox's name
 * says what and where ("Description, proposed from Audible, changes yours"), starting with the word
 * beside it.
 */
@Composable
private fun FieldRow(
    field: FieldUi,
    viewerId: String?,
    session: BookMatchSession,
) {
    val label = fieldLabel(field.field)
    val from = sourcesText(field.proposed.sources)
    val edited = field.state == FieldState.USER_EDITED
    Div(attrs = {
        classes("bmx-field")
        attr("data-field", field.field.name)
    }) {
        Div(attrs = { classes("bmx-field-h") }) {
            Label(attrs = { classes("f-check") }) {
                CheckboxInput(checked = field.isTicked) {
                    attr("aria-label", fieldName(label, from, field.state))
                    onChange { event -> session.setFieldTicked(field.field, event.value) }
                }
                Text(label)
            }
            if (edited) Span(attrs = { classes("bmx-edited") }) { Text("You edited this") }
        }
        if (field.options.size > 1) SourceSwitch(field, label, session)
        Dl(attrs = { classes("bmx-vals") }) {
            Value("Yours", valueText(field.current), long = false)
            Value("Proposed · from $from", valueText(field.proposed.value), long = field.field == BookField.DESCRIPTION)
        }
        if (edited) P(attrs = { classes("bmx-edited-note") }) { Text(editedByText(field.handEdit, viewerId)) }
    }
}

private fun fieldName(
    label: String,
    from: String,
    state: FieldState,
): String =
    when (state) {
        FieldState.FILLS_GAP -> "$label, proposed from $from, fills a gap"
        FieldState.USER_EDITED -> "$label, proposed from $from, you edited this"
        FieldState.CHANGES, FieldState.SAME -> "$label, proposed from $from, changes yours"
    }

/** "Audible | Hardcover | Keep yours" as one radio group — the checkbox and the switch are one value. */
@Composable
private fun SourceSwitch(
    field: FieldUi,
    label: String,
    session: BookMatchSession,
) {
    Fieldset(attrs = { classes("bmx-fieldset") }) {
        Legend(attrs = { classes("sr-only") }) { Text("$label source") }
        Div(attrs = { classes("bmx-src") }) {
            field.options.forEach { option ->
                Segment(
                    group = "bmx-src-${field.field.name}",
                    checked = (field.choice as? FieldChoice.Option)?.optionId == option.optionId,
                    text = sourcesText(option.sources),
                    onChoose = { session.chooseSource(field.field, FieldChoice.Option(option.optionId)) },
                )
            }
            if (field.canKeepYours) {
                Segment(
                    group = "bmx-src-${field.field.name}",
                    checked = field.choice == FieldChoice.KeepCurrent,
                    text = "Keep yours",
                    onChoose = { session.chooseSource(field.field, FieldChoice.KeepCurrent) },
                )
            }
        }
    }
}

@Composable
private fun Segment(
    group: String,
    checked: Boolean,
    text: String,
    onChoose: () -> Unit,
) {
    Label(attrs = { classes("bmx-seg") }) {
        RadioInput(checked = checked) {
            attr("name", group)
            onChange { if (it.value) onChoose() }
        }
        Text(text)
    }
}

/** One side of Yours → Proposed. Long text shows three lines and Read all. */
@Composable
private fun Value(
    term: String,
    value: String,
    long: Boolean,
) {
    var expanded by remember(value) { mutableStateOf(false) }
    val clamps = long && value.length > CLAMP_AFTER_CHARS
    Div(attrs = { classes("bmx-val") }) {
        Dt { Text(term) }
        Dd(attrs = { if (clamps && !expanded) classes("is-clamped") }) { Text(value) }
        if (clamps) {
            Dd {
                DomButton(attrs = {
                    classes("lnk", "bmx-more")
                    attr("type", "button")
                    attr("aria-expanded", expanded.toString())
                    onClick { expanded = !expanded }
                }) { Text(if (expanded) "Show less" else "Read all") }
            }
        }
    }
}

/** Genres and moods: yours, kept unless you remove one; suggestions, each a toggle with its sources. */
@Composable
private fun LabelsSection(
    genres: LabelSetUi,
    moods: LabelSetUi,
    session: BookMatchSession,
) {
    val hasGenres = genres.yours.isNotEmpty() || genres.suggested.isNotEmpty()
    val hasMoods = moods.yours.isNotEmpty() || moods.suggested.isNotEmpty()
    if (!hasGenres && !hasMoods) return
    ReviewSection(SECTION_LABELS, "Genres & moods") {
        if (hasGenres) LabelSet("Genres", LabelKind.GENRES, genres, session)
        if (hasMoods) LabelSet("Moods", LabelKind.MOODS, moods, session)
        P(attrs = { classes("bmx-note") }) { Text("Tags are yours. Matching never changes them.") }
    }
}

@Composable
private fun LabelSet(
    title: String,
    kind: LabelKind,
    set: LabelSetUi,
    session: BookMatchSession,
) {
    H4(attrs = { classes("bmx-sub-t") }) { Text(title) }
    if (set.yours.isNotEmpty()) {
        P(attrs = { classes("bmx-note") }) { Text("Yours, kept") }
        Ul(attrs = {
            classes("bmx-chips")
            attr("aria-label", "$title, yours")
        }) {
            set.yours.forEach { yours ->
                Li(attrs = {
                    classes("bmx-chip")
                    if (yours.removed) classes("is-removed")
                }) {
                    Span(attrs = { classes("bmx-chip-l") }) { Text(yours.label) }
                    DomButton(attrs = {
                        classes("bmx-chip-x")
                        attr("type", "button")
                        if (yours.removed) {
                            attr("aria-label", "Keep ${yours.label}")
                            onClick { session.restoreYourLabel(kind, yours.label) }
                        } else {
                            attr("aria-label", "Remove ${yours.label}")
                            onClick { session.removeYourLabel(kind, yours.label) }
                        }
                    }) {
                        if (yours.removed) Text("Keep") else Icon(WebIcon.X, size = SMALL_ICON)
                    }
                }
            }
        }
    }
    if (set.suggested.isNotEmpty()) {
        P(attrs = { classes("bmx-note") }) { Text("Suggested") }
        Ul(attrs = {
            classes("bmx-chips")
            attr("aria-label", "$title, suggested")
        }) {
            set.suggested.forEach { suggestion ->
                val from = sourcesText(suggestion.sources)
                Li {
                    DomButton(attrs = {
                        classes("bmx-sug")
                        attr("type", "button")
                        attr("aria-pressed", suggestion.selected.toString())
                        attr("aria-label", "${suggestion.label}, from $from")
                        onClick { session.toggleSuggestion(kind, suggestion.label) }
                    }) {
                        Text(suggestion.label)
                        Span(attrs = { classes("bmx-sug-src") }) { Text(from) }
                    }
                }
            }
        }
    }
}

/** Chapter names: include them or not, the first rows, Show all; a different edition is said, not applied. */
@Composable
private fun ChapterNamesSection(
    names: ChapterNamesUi,
    session: BookMatchSession,
) {
    when (names) {
        ChapterNamesUi.Hidden -> Unit

        is ChapterNamesUi.CountMismatch -> {
            ReviewSection(SECTION_CHAPTERS, "Chapter names") {
                P(attrs = { classes("bmx-note") }) {
                    Text(
                        "${names.source.label} has ${names.theirs} chapters and yours has ${names.yours}, " +
                            "so its names can't be matched.",
                    )
                }
            }
        }

        is ChapterNamesUi.Available -> AvailableChapterNames(names, session)
    }
}

@Composable
private fun AvailableChapterNames(
    names: ChapterNamesUi.Available,
    session: BookMatchSession,
) {
    var showAll by remember { mutableStateOf(false) }
    val total = names.rows.size + names.unchangedCount
    val source = names.source.label
    ReviewSection(SECTION_CHAPTERS, "Chapter names") {
        Label(attrs = { classes("f-check") }) {
            CheckboxInput(checked = names.included) {
                onChange { event -> session.setChapterNamesIncluded(event.value) }
            }
            Text("Apply chapter names")
        }
        P(attrs = { classes("bmx-note") }) {
            Text(
                "${names.rows.size} of $total chapters get names from $source. " +
                    "The other ${names.unchangedCount} already match.",
            )
        }
        val rows = if (showAll) names.rows else names.rows.take(CHAPTERS_SHOWN)
        Ul(attrs = { classes("bmx-chapters") }) {
            rows.forEach { row ->
                Li(attrs = { classes("bmx-chapter") }) {
                    Label(attrs = { classes("f-check") }) {
                        CheckboxInput(checked = row.selected && names.included) {
                            attr("aria-label", "Chapter ${row.ordinal + 1}: ${row.yours} becomes ${row.theirs}")
                            if (!names.included) attr("disabled", "")
                            onChange { session.toggleChapter(row.ordinal) }
                        }
                        Span(attrs = { classes("bmx-was") }) { Text(row.yours) }
                        Icon(WebIcon.ArrowRight, size = SMALL_ICON)
                        Span { Text(row.theirs) }
                    }
                }
            }
        }
        if (names.rows.size > CHAPTERS_SHOWN) {
            KitButton(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = { showAll = !showAll }, attrs = {
                attr("aria-expanded", showAll.toString())
            }) { Text(if (showAll) "Show less" else "Show all ${names.rows.size}") }
        }
    }
}

/** "6 fields already match", collapsed; Length counts when it is the same. */
@Composable
private fun AlreadySameSection(
    same: List<BookField>,
    lengthSame: Boolean,
) {
    val names = same.map(::fieldLabel) + if (lengthSame) listOf("Length") else emptyList()
    if (names.isEmpty()) return
    ReviewSection(SECTION_SAME, "Already the same") {
        Details(attrs = { classes("bmx-same") }) {
            Summary { Text(if (names.size == 1) "1 field already matches" else "${names.size} fields already match") }
            P(attrs = { classes("bmx-note") }) { Text(names.joinToString(", ")) }
        }
    }
}

private const val COVER_GROUP = "bmx-cover"
private const val COVER_WIDTH = 176
private const val CHAPTERS_SHOWN = 3
private const val CLAMP_AFTER_CHARS = 180
