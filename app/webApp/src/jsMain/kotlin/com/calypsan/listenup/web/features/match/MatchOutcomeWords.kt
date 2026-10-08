package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.client.presentation.match.ApplySummary
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi

/*
 * What Match details says about outcomes on web — the Apply bar, the receipt, See what changed and
 * Find's failures — as plain functions of the shared UI types, beside MatchWords.kt's sentences
 * about the matches themselves. The English text of en.json's `match` group.
 */

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
internal fun countdownText(seconds: Int): String =
    "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

/** "<message> Nothing was changed." — once, however the message ends. */
internal fun nothingChanged(message: String): String =
    if (message.contains(NOTHING_WAS_CHANGED)) message else "$message $NOTHING_WAS_CHANGED"

internal const val NOTHING_WAS_CHANGED = "Nothing was changed."

private const val SECONDS_PER_MINUTE = 60
