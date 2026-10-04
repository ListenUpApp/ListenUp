package com.calypsan.listenup.web.features.metadata

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.MetadataField
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import com.calypsan.listenup.client.presentation.metadata.PreviewLoadState
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Phase two: choose what to take.
 *
 * **Nothing is applied until Apply, and only what is ticked is applied.** That is the whole point
 * of this screen: a match is a suggestion, not a replacement, and a reader who has spent an evening
 * fixing a title does not want it overwritten because the cover was wrong.
 *
 * Every field that came from somewhere other than the matched edition says so. A title merged in
 * from iTunes because Audible had none is still worth taking — but the reader is entitled to know
 * it is not what the edition they picked says.
 */
@Composable
internal fun MetadataPreviewPhase(
    ready: PreviewLoadState.Ready,
    region: MetadataLocale,
    onRegion: (MetadataLocale) -> Unit,
    onBackToResults: () -> Unit,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onToggleMood: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onSelectCover: (String) -> Unit,
    onKeepCurrentCover: () -> Unit,
    currentCoverUrl: String?,
    onReviewChapters: () -> Unit,
    onApply: () -> Unit,
) {
    val preview = ready.preview

    MatchedHero(preview, region, onBackToResults)
    RegionSelector(region, onRegion)

    if (ready.previewNotFound) {
        P(attrs = {
            classes("mdx-err")
            attr("role", "alert")
        }) { Text("Book not found on Audible catalog. Try a different region:") }
        return
    }

    if (!preview.hasAnyData()) {
        EmptyState(
            title = "No metadata available",
            body = "Try selecting a different region above.",
            look = EmptyLook.Inset,
        )
        return
    }

    FormSection(
        title = "Cover",
    ) { CoverField(ready, currentCoverUrl, onToggleField, onSelectCover, onKeepCurrentCover) }
    FormSection(title = "Identity") {
        IdentityFields(ready, onToggleField, onToggleAuthor, onToggleNarrator, onToggleSeries)
    }
    FormSection(title = "Details") { DetailFields(ready, onToggleField) }
    FormSection(title = "Classification") { ClassificationFields(ready, onToggleGenre, onToggleMood, onToggleTag) }

    ChapterNamesRow(ready.chapterSuggestion, onReviewChapters)

    ApplyBar(ready, onApply)
}

/** The edition the reader picked, and the way back to the ones they did not. */
@Composable
private fun MatchedHero(
    preview: MetadataBook,
    region: MetadataLocale,
    onBackToResults: () -> Unit,
) {
    Div(attrs = { classes("mdx-hero") }) {
        preview.coverUrl?.let { url ->
            Img(src = url, alt = "", attrs = {
                classes("mdx-hero-c")
                attr("referrerpolicy", "no-referrer")
            })
        }
        Div(attrs = { classes("mdx-hero-m") }) {
            Div(attrs = { classes("mdx-hero-t") }) { Text(preview.title) }
            preview.subtitle?.takeIf { it.isNotBlank() }?.let {
                Div(attrs = { classes("mdx-hero-s") }) { Text(it) }
            }
            Span(attrs = { classes("mdx-src") }) { Text("Audible · ${region.displayName}") }
        }
        Button(kind = ButtonKind.Secondary, onClick = { onBackToResults() }) { Text("Back to results") }
    }
}

/**
 * The artwork, and which one.
 *
 * One choice, shown as one: the book's current cover or one of the match's candidates, and exactly one
 * of them is marked — the one Apply writes. The checkbox is the same choice from the other side: unticked
 * is "keep the current cover", ticked takes the marked candidate. The row names the chosen cover's real
 * source; it never assumes Audible.
 */
@Composable
private fun CoverField(
    ready: PreviewLoadState.Ready,
    currentCoverUrl: String?,
    onToggleField: (MetadataField) -> Unit,
    onSelectCover: (String) -> Unit,
    onKeepCurrentCover: () -> Unit,
) {
    val applied = ready.appliedCover
    FieldRow(
        label = "Cover",
        value = applied?.let { "New artwork from ${it.label}" } ?: "Keep the current cover",
        checked = ready.selections.cover,
        source = null,
        onToggle = { onToggleField(MetadataField.COVER) },
    )
    if (ready.coverEntries.isEmpty()) return

    Div(attrs = { classes("mdx-covers") }) {
        CoverTile(
            label = "Current cover",
            resolution = null,
            isSelected = ready.keepsCurrentCover,
            onPick = onKeepCurrentCover,
        ) {
            // A book with no artwork yet still offers this tile: keeping what it has is a choice.
            if (currentCoverUrl != null) CoverArt(currentCoverUrl) else Div(attrs = { classes("mdx-cover-i") })
        }
        ready.coverEntries.forEach { entry ->
            key(entry.url) {
                CoverTile(
                    label = entry.label,
                    resolution = entry.resolution,
                    isSelected = applied?.url == entry.url,
                    onPick = { onSelectCover(entry.url) },
                ) { CoverArt(entry.url) }
            }
        }
    }
}

@Composable
private fun CoverTile(
    label: String,
    resolution: String?,
    isSelected: Boolean,
    onPick: () -> Unit,
    art: @Composable () -> Unit,
) {
    Button(attrs = {
        classes("mdx-cover")
        if (isSelected) classes("on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-pressed", isSelected.toString())
        onClick { onPick() }
    }) {
        art()
        Span(attrs = { classes("mdx-cover-l") }) { Text(label) }
        resolution?.let { Span(attrs = { classes("mdx-cover-r") }) { Text(it) } }
    }
}

@Composable
private fun CoverArt(url: String) {
    Img(src = url, alt = "", attrs = {
        classes("mdx-cover-i")
        attr("loading", "lazy")
        attr("referrerpolicy", "no-referrer")
    })
}

@Composable
private fun IdentityFields(
    ready: PreviewLoadState.Ready,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
) {
    val preview = ready.preview
    FieldRow("Title", preview.title, ready.selections.title, ready.fallbackSourceFor(BookField.TITLE)) {
        onToggleField(MetadataField.TITLE)
    }
    preview.subtitle?.takeIf { it.isNotBlank() }?.let {
        FieldRow("Subtitle", it, ready.selections.subtitle, ready.fallbackSourceFor(BookField.SUBTITLE)) {
            onToggleField(MetadataField.SUBTITLE)
        }
    }
    // ⛔ Keyed `asin ?: name`, exactly as the ViewModel selects contributors and the server applies
    // them. Audible usually sends narrators without an ASIN; skipping those rows hid a narrator the
    // apply still wrote.
    ValueRows(
        "Authors",
        preview.authors.map { c ->
            (c.asin ?: c.name) to c.name
        },
        ready.selections.selectedAuthors,
        onToggleAuthor,
    )
    ValueRows(
        "Narrators",
        preview.narrators.map { c -> (c.asin ?: c.name) to c.name },
        ready.selections.selectedNarrators,
        onToggleNarrator,
    )
    // Series are selected by ASIN alone (the ViewModel never selects one without), so an ASIN-less
    // series is neither offered nor written.
    ValueRows(
        "Series",
        preview.series.mapNotNull { s -> s.asin?.let { it to seriesLabel(s.title, s.sequence) } },
        ready.selections.selectedSeries,
        onToggleSeries,
    )
}

@Composable
private fun DetailFields(
    ready: PreviewLoadState.Ready,
    onToggleField: (MetadataField) -> Unit,
) {
    val preview = ready.preview
    preview.description?.takeIf { it.isNotBlank() }?.let {
        FieldRow(
            label = "Description",
            value = it,
            checked = ready.selections.descriptionSelected,
            source = ready.fallbackSourceFor(BookField.DESCRIPTION),
            clamp = true,
        ) { onToggleField(MetadataField.DESCRIPTION) }
    }
    preview.publisher?.takeIf { it.isNotBlank() }?.let {
        FieldRow("Publisher", it, ready.selections.publisher, ready.fallbackSourceFor(BookField.PUBLISHER)) {
            onToggleField(MetadataField.PUBLISHER)
        }
    }
    preview.releaseDate?.takeIf { it.isNotBlank() }?.let {
        // PUBLISH_YEAR, not a RELEASE_DATE — the provenance vocabulary names the year the
        // providers actually disagree about; the release date is how this UI shows it.
        FieldRow("Release date", it, ready.selections.releaseDate, ready.fallbackSourceFor(BookField.PUBLISH_YEAR)) {
            onToggleField(MetadataField.RELEASE_DATE)
        }
    }
    preview.language?.takeIf { it.isNotBlank() }?.let {
        FieldRow("Language", it, ready.selections.language, ready.fallbackSourceFor(BookField.LANGUAGE)) {
            onToggleField(MetadataField.LANGUAGE)
        }
    }
}

@Composable
private fun ClassificationFields(
    ready: PreviewLoadState.Ready,
    onToggleGenre: (String) -> Unit,
    onToggleMood: (String) -> Unit,
    onToggleTag: (String) -> Unit,
) {
    // ⛔ The CANDIDATES, not the match's own lists. The ViewModel narrows Audible's labels to the
    // ones this library actually has, so a tick always lands on a real genre rather than minting
    // one from a scraped string.
    ValueRows(
        label = "Genres",
        values = ready.genreCandidates.map { it to it },
        selected = ready.selections.selectedGenres,
        onToggle = onToggleGenre,
        sourceOf = ready::genreSourceFor,
    )
    ValueRows(
        label = "Moods",
        values = ready.moodCandidates.map { it to it },
        selected = ready.selections.selectedMoods,
        onToggle = onToggleMood,
        sourceOf = ready::moodSourceFor,
    )
    ValueRows("Tags", ready.tagCandidates.map { it to it }, ready.selections.selectedTags, onToggleTag)
}

/** One simple field: what it would become, whether to take it, and where it came from. */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    checked: Boolean,
    source: String?,
    clamp: Boolean = false,
    onToggle: () -> Unit,
) {
    val fromId = "mdx-from-${label.idSlug()}"
    Div(attrs = { classes("mdx-field") }) {
        CheckboxField(
            label = label,
            checked = checked,
            onChange = { onToggle() },
            describedBy = source?.let { fromId },
        )
        Div(attrs = {
            classes("mdx-field-v")
            if (clamp) classes("clamp")
        }) { Text(value) }
        source?.let {
            Span(attrs = {
                classes("mdx-from")
                id(fromId)
            }) { Text("from $it") }
        }
    }
}

/**
 * A list field — every value its own decision, because taking all of them rarely is one. [sourceOf] names
 * where a value came from when the match proposed it from another source ("from Hardcover", #1542); a value
 * the book already had claims nothing.
 */
@Composable
private fun ValueRows(
    label: String,
    values: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    sourceOf: (String) -> String? = { null },
) {
    if (values.isEmpty()) return
    val labelId = "mdx-values-${label.idSlug()}"
    // A named group, so "Space Opera, checkbox" is heard as one of the Genres; and each value's
    // provenance is its description, so a Hardcover value does not sound like any other.
    Div(attrs = {
        classes("mdx-values")
        attr("role", "group")
        attr("aria-labelledby", labelId)
    }) {
        Span(attrs = {
            classes("mdx-values-l")
            id(labelId)
        }) { Text(label) }
        values.forEachIndexed { index, (valueKey, text) ->
            key(valueKey) {
                val source = sourceOf(valueKey)
                val fromId = "$labelId-from-$index"
                CheckboxField(
                    label = text,
                    checked = valueKey in selected,
                    onChange = { onToggle(valueKey) },
                    describedBy = source?.let { fromId },
                )
                source?.let {
                    Span(attrs = {
                        classes("mdx-from")
                        id(fromId)
                    }) { Text("from $it") }
                }
            }
        }
    }
}

/**
 * The chapter-name offer.
 *
 * ⛔ Count-gated, and never force-aligned. A different chapter count means a different edition, and
 * mapping 34 Audible names onto 31 local chapters would put the wrong name on every one of them
 * from the first mismatch onward. That case is shown, with the numbers, and refused.
 */
@Composable
private fun ChapterNamesRow(
    suggestion: ChapterSuggestion,
    onReview: () -> Unit,
) {
    when (suggestion) {
        // No chapters here or none there — nothing to offer, so nothing is said.
        ChapterSuggestion.Unavailable -> {
            Unit
        }

        is ChapterSuggestion.CountMismatch -> {
            Div(attrs = { classes("mdx-chapters", "off") }) {
                Span(attrs = { classes("mdx-chapters-l") }) { Text("Chapter names") }
                P {
                    Text(
                        "${suggestion.audibleCount} Audible chapters → your ${suggestion.localCount} — " +
                            "different edition, unavailable.",
                    )
                }
            }
        }

        is ChapterSuggestion.Available -> {
            Div(attrs = { classes("mdx-chapters") }) {
                Span(attrs = { classes("mdx-chapters-l") }) { Text("Chapter names") }
                P { Text("${suggestion.rows.size} chapters matched") }
                Button(
                    kind = ButtonKind.Secondary,
                    onClick = { onReview() },
                    attrs = {
                        classes("mdx-review")
                    },
                ) { Text("Review & apply chapter names") }
            }
        }
    }
}

/** Apply, what it will draw from, and why it might be refused. */
@Composable
private fun ApplyBar(
    ready: PreviewLoadState.Ready,
    onApply: () -> Unit,
) {
    Div(attrs = { classes("mdx-apply") }) {
        ready.applyError?.let {
            P(attrs = {
                classes("mdx-err")
                attr("role", "alert")
            }) { Text(it) }
        }
        if (ready.contributingSources.isNotEmpty()) {
            Span(attrs = { classes("mdx-merged") }) {
                Text("Merged from ${ready.contributingSources.joinToString(", ")}")
            }
        }
        Button(
            kind = ButtonKind.Primary,
            onClick = { onApply() },
            attrs = {
                // Nothing ticked is nothing to apply, and a button that reports success for a change
                // nobody made is the lie this whole screen exists to avoid.
                disabledWhen(ready.isApplying || !ready.selections.hasAnySelected())
            },
        ) {
            Icon(WebIcon.Check, size = SMALL_ICON)
            Text(if (ready.isApplying) "Applying…" else "Apply selected metadata")
        }
    }
}

/** True when at least one field is ticked — what gates Apply. */
internal fun MetadataSelections.hasAnySelected(): Boolean =
    cover ||
        title ||
        subtitle ||
        descriptionSelected ||
        publisher ||
        releaseDate ||
        language ||
        selectedAuthors.isNotEmpty() ||
        selectedNarrators.isNotEmpty() ||
        selectedSeries.isNotEmpty() ||
        selectedGenres.isNotEmpty() ||
        selectedMoods.isNotEmpty() ||
        selectedTags.isNotEmpty()

/** True when the match carries anything worth showing at all. */
internal fun MetadataBook.hasAnyData(): Boolean =
    coverUrl != null ||
        title.isNotBlank() ||
        !subtitle.isNullOrBlank() ||
        authors.isNotEmpty() ||
        narrators.isNotEmpty() ||
        series.isNotEmpty() ||
        genres.isNotEmpty() ||
        moods.isNotEmpty() ||
        tags.isNotEmpty() ||
        !description.isNullOrBlank() ||
        !publisher.isNullOrBlank() ||
        !language.isNullOrBlank() ||
        !releaseDate.isNullOrBlank()

/** "Mistborn · 3.5", or just the name when Audible gives no position. */
internal fun seriesLabel(
    title: String,
    sequence: String?,
): String = if (sequence.isNullOrBlank()) title else "$title · $sequence"

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16

/** A field's label as an id fragment: "Narrators" → "narrators". */
private fun String.idSlug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
