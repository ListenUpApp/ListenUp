package com.calypsan.listenup.web.features.metadata

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.CoverEntry
import com.calypsan.listenup.client.presentation.metadata.MetadataField
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import com.calypsan.listenup.client.presentation.metadata.PreviewLoadState
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
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
    onSelectCover: (String?) -> Unit,
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
        Div(attrs = { classes("mdx-empty") }) {
            H2 { Text("No metadata available") }
            P { Text("Try selecting a different region above.") }
        }
        return
    }

    FormSection(title = "Cover") { CoverField(ready, onToggleField, onSelectCover) }
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
        Button(attrs = {
            classes(BTN_SECONDARY)
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { onBackToResults() }
        }) { Text("Back to results") }
    }
}

/**
 * The artwork, and which one.
 *
 * The cover checkbox and the cover *choice* are two decisions: taking new artwork at all, and which
 * of several sources it comes from. Ticking the box without choosing takes the matched edition's
 * own cover, which is the answer most readers want and never have to think about.
 */
@Composable
private fun CoverField(
    ready: PreviewLoadState.Ready,
    onToggleField: (MetadataField) -> Unit,
    onSelectCover: (String?) -> Unit,
) {
    FieldRow(
        label = "Cover",
        value = "New artwork from Audible",
        checked = ready.selections.cover,
        source = ready.coverSourceLabel,
        onToggle = { onToggleField(MetadataField.COVER) },
    )
    if (!ready.selections.cover || ready.coverEntries.isEmpty()) return

    Div(attrs = { classes("mdx-covers") }) {
        ready.coverEntries.forEach { entry -> CoverOption(entry, ready.selectedCoverUrl, onSelectCover) }
    }
}

@Composable
private fun CoverOption(
    entry: CoverEntry,
    selectedUrl: String?,
    onSelectCover: (String?) -> Unit,
) {
    val isSelected = entry.url == selectedUrl
    Button(attrs = {
        classes("mdx-cover")
        if (isSelected) classes("on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-pressed", isSelected.toString())
        onClick { onSelectCover(entry.url) }
    }) {
        Img(src = entry.url, alt = "", attrs = {
            classes("mdx-cover-i")
            attr("loading", "lazy")
            attr("referrerpolicy", "no-referrer")
        })
        Span(attrs = { classes("mdx-cover-l") }) { Text(entry.label) }
        entry.resolution?.let { Span(attrs = { classes("mdx-cover-r") }) { Text(it) } }
    }
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
    // ⛔ Keyed by ASIN, and skipped when Audible omits one. The ViewModel's selection sets are ASIN
    // sets, so a contributor with no ASIN has no key to be selected by — offering a tick that
    // cannot be recorded is worse than not offering one.
    ValueRows(
        "Authors",
        preview.authors.mapNotNull { c ->
            c.asin?.let { it to c.name }
        },
        ready.selections.selectedAuthors,
        onToggleAuthor,
    )
    ValueRows(
        "Narrators",
        preview.narrators.mapNotNull { c -> c.asin?.let { it to c.name } },
        ready.selections.selectedNarrators,
        onToggleNarrator,
    )
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
    ValueRows("Genres", ready.genreCandidates.map { it to it }, ready.selections.selectedGenres, onToggleGenre)
    ValueRows("Moods", ready.moodCandidates.map { it to it }, ready.selections.selectedMoods, onToggleMood)
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
    Div(attrs = { classes("mdx-field") }) {
        CheckboxField(label = label, checked = checked, onChange = { onToggle() })
        Div(attrs = {
            classes("mdx-field-v")
            if (clamp) classes("clamp")
        }) { Text(value) }
        source?.let { Span(attrs = { classes("mdx-from") }) { Text("from $it") } }
    }
}

/** A list field — every value its own decision, because taking all of them rarely is one. */
@Composable
private fun ValueRows(
    label: String,
    values: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (values.isEmpty()) return
    Div(attrs = { classes("mdx-values") }) {
        Span(attrs = { classes("mdx-values-l") }) { Text(label) }
        values.forEach { (key, text) ->
            CheckboxField(label = text, checked = key in selected, onChange = { onToggle(key) })
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
                Button(attrs = {
                    classes(BTN_SECONDARY, "mdx-review")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onReview() }
                }) { Text("Review & apply chapter names") }
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
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, VALUE_BUTTON)
            // Nothing ticked is nothing to apply, and a button that reports success for a change
            // nobody made is the lie this whole screen exists to avoid.
            disabledWhen(ready.isApplying || !ready.selections.hasAnySelected())
            onClick { onApply() }
        }) {
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

private const val BTN_SECONDARY = "btn-o"

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16
