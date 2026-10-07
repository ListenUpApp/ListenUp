package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.FoundIn
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.IdentifierKind
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.match.ApplySummary
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import kotlin.js.Date
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/*
 * Every sentence Match details says on web, as plain functions of the shared UI types. They are the
 * English text of en.json's `match` group — web speaks literal English, as the rest of webApp does —
 * gathered here so the page files hold layout and nothing else, and so a spec can pin a sentence
 * without mounting a page.
 *
 * Provider names are never written here: a source is only ever its `label`, so a new provider needs
 * no change on web.
 */

/** "A", "A and B", "A, B and C" — the canvas's list voice (match.list_two / match.list_many). */
internal fun humanList(items: List<String>): String =
    when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

/** The labels of [sources], as one list. */
internal fun sourcesText(sources: List<MetadataSource>): String = humanList(sources.map { it.label }.distinct())

/** "16h 10m". */
internal fun lengthText(durationMs: Long): String = DurationFormatter.hoursMinutes(durationMs.milliseconds)

/** "Unabridged", "Abridged", "Dramatized". */
internal fun formatText(format: EditionFormat): String =
    when (format) {
        EditionFormat.UNABRIDGED -> "Unabridged"
        EditionFormat.ABRIDGED -> "Abridged"
        EditionFormat.DRAMATIZED -> "Dramatized"
    }

/** "36 chapters", "1 chapter". */
internal fun chaptersText(count: Int): String = if (count == 1) "1 chapter" else "$count chapters"

/** Narrators as a row shows them: names, or "Full cast" past three. */
internal fun narratorsText(names: List<String>): String? =
    when {
        names.isEmpty() -> null
        names.size > FULL_CAST_AFTER -> "Full cast"
        else -> names.joinToString(", ")
    }

/** One reason a candidate ranks where it does (match.reason_*). */
internal fun reasonText(reason: MatchReason): String =
    when (reason) {
        MatchReason.SameNarrator -> "Same narrator"
        MatchReason.DifferentNarrators -> "Different narrators"
        MatchReason.SameLength -> "Same length"
        is MatchReason.LengthWithin -> "Length within ${reason.minutes} min"
        is MatchReason.LengthDiffers -> lengthDeltaText(reason.deltaMinutes)
        MatchReason.LengthUnknown -> "Length unknown"
        is MatchReason.SameChapterCount -> chaptersText(reason.count)
        is MatchReason.DifferentChapterCount -> "${reason.theirs} chapters, not yours"
        is MatchReason.DifferentStore -> "${reason.region.displayName} store"
        is MatchReason.DifferentEdition -> "${formatText(reason.format)} edition"
    }

/** "6h 26m shorter" / "12m longer". */
internal fun lengthDeltaText(deltaMinutes: Int): String {
    val amount = DurationFormatter.hoursMinutes(abs(deltaMinutes).minutes)
    return if (deltaMinutes < 0) "$amount shorter" else "$amount longer"
}

/** A candidate's metadata line: "Ray Porter · 16h 10m · 2021 · Unabridged". */
internal fun candidateMetaText(candidate: CandidateUi): String =
    listOfNotNull(
        narratorsText(candidate.narrators),
        candidate.durationMs?.let(::lengthText),
        candidate.year?.toString(),
        candidate.format?.let(::formatText),
    ).joinToString(" · ")

/** "Found in Audible, Hardcover and iTunes". */
internal fun foundInText(foundIn: List<FoundIn>): String = "Found in " + sourcesText(foundIn.map { it.source })

/** Your copy's line: "16h 10m · Ray Porter · 36 chapters". */
internal fun yourCopyMetaText(copy: YourCopyUi): String =
    listOfNotNull(
        copy.durationMs?.let(::lengthText),
        narratorsText(copy.narrators),
        copy.chapterCount?.let(::chaptersText),
    ).joinToString(" · ")

/** "Started from your Audible link, then title, author and length." — or null when Find took no steps. */
internal fun stepsText(steps: List<SearchStep>): String? {
    if (steps.isEmpty()) return null
    val said = steps.map(::stepText).reduce { first, next -> "$first, then $next" }
    return "Started from $said."
}

private fun stepText(step: SearchStep): String =
    when (step) {
        is SearchStep.ExistingLink -> "your ${step.source.label} link"
        is SearchStep.Identifier -> if (step.kind == IdentifierKind.ASIN) "the ASIN" else "the ISBN"
        SearchStep.TitleAuthorLength -> "title, author and length"
        is SearchStep.YourQuery -> "your search “${step.query}”"
    }

/** What a field is called on screen (match.field_*). */
internal fun fieldLabel(field: BookField): String =
    when (field) {
        BookField.TITLE -> "Title"
        BookField.SORT_TITLE -> "Sort title"
        BookField.SUBTITLE -> "Subtitle"
        BookField.DESCRIPTION -> "Description"
        BookField.PUBLISHER -> "Publisher"
        BookField.PUBLISH_YEAR -> "Release date"
        BookField.LANGUAGE -> "Language"
        BookField.ISBN -> "ISBN"
        BookField.ASIN -> "ASIN"
        BookField.ABRIDGED -> "Abridged"
        BookField.EXPLICIT -> "Explicit"
        BookField.AUTHORS -> "Authors"
        BookField.NARRATORS -> "Narrators"
        BookField.SERIES -> "Series"
        BookField.GENRES -> "Genres"
        BookField.MOODS -> "Moods"
        BookField.TAGS -> "Tags"
        BookField.COVER -> "Cover"
        BookField.CHAPTERS -> "Chapters"
    }

/** A field's value as text; release dates are years (P2). Description HTML is read as plain text. */
internal fun valueText(value: FieldValue?): String =
    when (value) {
        null -> "—"
        is FieldValue.Text -> plainText(value.text).ifBlank { "—" }
        is FieldValue.People -> value.names.joinToString(", ").ifBlank { "—" }
        is FieldValue.SeriesEntries ->
            value.entries
                .joinToString(", ") { entry -> entry.sequence?.let { "${entry.name} #$it" } ?: entry.name }
                .ifBlank { "—" }
        is FieldValue.Year -> value.year.toString()
    }

/**
 * Catalogue descriptions arrive with HTML in them. They are shown as text, never as markup: paragraph
 * and line breaks become new lines, every other tag goes, and the few entities catalogues use are read.
 */
internal fun plainText(html: String): String =
    html
        .replace(BREAKS, "\n")
        .replace(TAGS, "")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(BLANK_LINES, "\n\n")
        .trim()

/**
 * "Edited by you, 12 Sep. Kept unless you tick it." — "you" when the viewer made the edit, their name
 * when the server knows it, "hand" otherwise; the date is left out when it is not known.
 */
internal fun editedByText(
    edit: HandEdit?,
    viewerId: String?,
): String {
    val who =
        when {
            edit?.byUserId != null && edit.byUserId == viewerId -> "you"
            !edit?.byName.isNullOrBlank() -> edit.byName
            else -> "hand"
        }
    val day = edit?.at?.takeIf { it > 0 }?.let(::dayMonthText)
    return if (day != null) "Edited by $who, $day. Kept unless you tick it." else "Edited by $who. Kept unless you tick it."
}

/** "12 Sep", in the reader's own time zone. */
internal fun dayMonthText(epochMs: Long): String {
    val date = Date(epochMs.toDouble())
    return "${date.getDate()} ${MONTHS[date.getMonth()]}"
}

/** The Apply bar's words: "5 fields · cover · 16 chapter names", or "Nothing selected". */
internal fun applyBarText(summary: ApplySummary): String {
    if (!summary.canApply) return "Nothing selected"
    return listOfNotNull(
        summary.fieldCount.takeIf { it > 0 }?.let { if (it == 1) "1 field" else "$it fields" },
        "cover".takeIf { summary.coverChanges },
        summary.chapterNameCount.takeIf { it > 0 }?.let { if (it == 1) "1 chapter name" else "$it chapter names" },
    ).joinToString(" · ")
}

/** The receipt's sentence: "Changed 5 fields, cover from Hardcover, 16 chapter names". */
internal fun receiptText(receipt: MatchReceiptUi): String {
    val parts =
        listOfNotNull(
            receipt.fieldCount.takeIf { it > 0 }?.let { if (it == 1) "1 field" else "$it fields" },
            receipt.coverSource?.let { "cover from ${it.label}" },
            receipt.chapterNameCount.takeIf { it > 0 }?.let { if (it == 1) "1 chapter name" else "$it chapter names" },
        )
    return if (parts.isEmpty()) "Matched. Nothing needed changing." else "Changed " + parts.joinToString(", ")
}

/** One line of See what changed per thing a change did, with where it came from. */
internal fun changeLines(change: AppliedChange): List<String> =
    when (change) {
        is AppliedChange.Field -> listOf("${fieldLabel(change.field)} · from ${change.source.label}")
        is AppliedChange.Cover -> listOf("Cover · from ${change.source.label}")
        is AppliedChange.Genres -> labelLines("Genres", change.added, change.removed)
        is AppliedChange.Moods -> labelLines("Moods", change.added, change.removed)
        is AppliedChange.ChapterNames -> listOf("${change.count} chapter names · from ${change.source.label}")
        is AppliedChange.Photo -> listOf("Photo · from ${change.source.label}")
        is AppliedChange.Biography -> listOf("Biography · from ${change.source.label}")
    }

private fun labelLines(
    kind: String,
    added: List<String>,
    removed: List<String>,
): List<String> =
    listOfNotNull(
        added.takeIf { it.isNotEmpty() }?.let { "$kind added: ${it.joinToString(", ")}" },
        removed.takeIf { it.isNotEmpty() }?.let { "$kind removed: ${it.joinToString(", ")}" },
    )

/** A failure's heading (W-05). */
internal fun failureTitle(failure: FindFailure): String =
    when (failure) {
        FindFailure.Offline -> "You're offline"
        is FindFailure.TimedOut -> "${failure.source.label} didn't answer in time"
        is FindFailure.RateLimited -> "${failure.source.label} asked us to slow down"
        is FindFailure.SourceFailed -> "${failure.source.label} didn't answer"
        is FindFailure.NotFoundInStore -> "No match in the ${failure.region.displayName} store"
        FindFailure.NothingFound -> "No matches"
        is FindFailure.Unexpected -> "Something went wrong"
    }

/** A failure's sentence under its heading. */
internal fun failureBody(failure: FindFailure): String =
    when (failure) {
        FindFailure.Offline -> "Matching needs the server, and the server needs its sources."
        is FindFailure.TimedOut -> "Nothing was changed. This usually clears in a moment."
        is FindFailure.RateLimited -> "Try again in ${failure.secondsRemaining} seconds."
        is FindFailure.SourceFailed -> "Nothing was changed. Try again in a moment."
        is FindFailure.NotFoundInStore -> "Your book may be listed in another store."
        FindFailure.NothingFound -> "Try a different search, or search by title."
        is FindFailure.Unexpected -> failure.error.message
    }

/** "0:30" — the rate limit's countdown on its Retry button. */
internal fun countdownText(seconds: Int): String = "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

/** "<message> Nothing was changed." — once, however the message ends. */
internal fun nothingChanged(message: String): String =
    if (message.contains(NOTHING_WAS_CHANGED)) message else "$message $NOTHING_WAS_CHANGED"

internal const val NOTHING_WAS_CHANGED = "Nothing was changed."

private const val FULL_CAST_AFTER = 3
private const val SECONDS_PER_MINUTE = 60
private val BREAKS = Regex("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>")
private val TAGS = Regex("<[^>]*>")
private val BLANK_LINES = Regex("\\n{3,}")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
