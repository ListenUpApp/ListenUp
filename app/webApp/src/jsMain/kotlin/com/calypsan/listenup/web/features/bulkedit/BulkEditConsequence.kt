package com.calypsan.listenup.web.features.bulkedit

import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState

/**
 * What one field promises to do.
 *
 * @property text the sentence under the field.
 * @property writes whether leaving the form as it stands would write to any book through this
 *   field. The form styles an armed field differently from a resting one, and this is the fact it
 *   styles on.
 */
class FieldConsequence(
    val text: String,
    val writes: Boolean,
)

/**
 * The sentence under a publishing field: what it will do, or what the books already say.
 *
 * ⛔ An untouched field and a touched one have to be unmistakable, because the safety of this whole
 * screen rests on reading them apart. An untouched one reports what the selection already agrees on
 * and writes nothing; a touched one is an instruction that will be written to as many books as its
 * sentence names.
 */
internal inline fun <reified T : BulkEdit> BulkEditUiState.Editing.consequenceOf(
    sharedValue: String?,
): FieldConsequence = armedConsequenceOf<T>() ?: untouchedConsequence(sharedValue)

/**
 * What an armed field of type [T] promises, or null when nothing has been typed or picked into it.
 *
 * Shared with the relation fields, which arm and disarm exactly as the text fields do but have no
 * "the books already agree" state to fall back to — a genre is not a value a selection can share,
 * it is a thing each book either carries or does not.
 */
internal inline fun <reified T : BulkEdit> BulkEditUiState.Editing.armedConsequenceOf(): FieldConsequence? {
    if (edits.filterIsInstance<T>().lastOrNull() == null) return null
    val affected = preview.firstOrNull { it.edit is T }?.affectedCount ?: 0
    return when {
        // ⛔ Not "written to 0 books". A field that changes nothing is armed but harmless, and
        // saying so plainly is what stops a reader clearing it in a panic.
        affected == 0 -> FieldConsequence("Written to no books — they already say this.", writes = false)

        bookCount == 1 -> FieldConsequence("Written to this book.", writes = true)

        // ⛔ No separate `affected == 1` arm. Android needs one because its plural lives in two
        // string resources; here the interpolation already reads "Written to 1 of 40 books.", and
        // sabotage proved the extra branch was dead — deleting it identical was a no-op.
        else -> FieldConsequence("Written to $affected of $bookCount books.", writes = true)
    }
}

/** What an untouched publishing field says: the value the books agree on, or that they do not. */
internal fun BulkEditUiState.Editing.untouchedConsequence(sharedValue: String?): FieldConsequence {
    val text =
        when {
            sharedValue != null && bookCount == 1 -> "This book says $sharedValue. Leave it and no book is written to."
            sharedValue != null -> "All $bookCount books say $sharedValue. Leave it and no book is written to."
            bookCount == 1 -> "This book has no value here. Leave it and no book is written to."
            else -> "Differs across $bookCount books. Leave it and no book is written to."
        }
    return FieldConsequence(text, writes = false)
}

/**
 * How much of the selection one instruction touches, in words.
 *
 * A single selected book gets its own sentence rather than the counted one: "1 of 1 books change"
 * is a sentence nobody needs to parse to learn something they already know.
 */
internal fun affectsText(
    affectedCount: Int,
    bookCount: Int,
): String =
    when {
        affectedCount == 0 -> "No books change"
        bookCount == 1 -> "This book changes"
        else -> "$affectedCount of $bookCount books change"
    }

/**
 * Why the rest of the selection is left alone, or null when there is no rest to account for.
 *
 * ⛔ Only the scalar instructions can name a value, so only they get a note. An "add genres" row has
 * no single word to put in the sentence, and a note that could not say what those books already
 * agree on would be noise. Silent too when the instruction changes every book — there is nothing
 * left to explain, and filler is how readers learn to skip the lines that matter.
 */
internal fun leftAloneNote(
    edit: BulkEdit,
    affectedCount: Int,
    bookCount: Int,
): String? {
    val value = valueOf(edit) ?: return null
    val leftAlone = bookCount - affectedCount
    return when {
        leftAlone <= 0 -> null
        leftAlone == 1 -> "1 already says $value, so it is left alone."
        else -> "$leftAlone already say $value, so they are left alone."
    }
}

/**
 * The field an instruction changes, as the form labels it.
 *
 * ⛔ Exhaustive over [BulkEdit] on purpose: a ninth instruction is a compile error here, which is
 * the only reliable reminder that a new field also needs a name in the preview. A row that could
 * not name itself would be worse than no row.
 */
internal fun labelOf(edit: BulkEdit): String =
    when (edit) {
        is BulkEdit.SetPublisher -> "Publisher"
        is BulkEdit.SetPublishYear -> "Publication year"
        is BulkEdit.SetLanguage -> "Language"
        is BulkEdit.AddToSeries -> "Add to series"
        is BulkEdit.AddContributors -> "Add contributors"
        is BulkEdit.AddGenres -> "Add genres"
        is BulkEdit.AddTags -> "Add tags"
        is BulkEdit.AddMoods -> "Add moods"
    }

/**
 * The value an instruction writes, when it is a single value a sentence can name.
 *
 * The collection instructions return null rather than a joined list: "Fantasy, Grimdark, Space Opera
 * already say…" is not a sentence, and half a sentence in a destructive preview is worse than none.
 */
private fun valueOf(edit: BulkEdit): String? =
    when (edit) {
        is BulkEdit.SetPublisher -> edit.publisher
        is BulkEdit.SetPublishYear -> edit.year.toString()
        is BulkEdit.SetLanguage -> edit.language
        is BulkEdit.AddToSeries -> null
        is BulkEdit.AddContributors -> null
        is BulkEdit.AddGenres -> null
        is BulkEdit.AddTags -> null
        is BulkEdit.AddMoods -> null
    }

/** What the Apply button says. The count is the promise, so it is on the button. */
internal fun applyLabel(
    changedBookCount: Int,
    isApplying: Boolean,
): String =
    when {
        isApplying -> "Applying…"
        changedBookCount == 0 -> "Change"
        changedBookCount == 1 -> "Change 1 book"
        else -> "Change $changedBookCount books"
    }

/** What a finished run reports. */
internal fun appliedLabel(changedCount: Int): String =
    if (changedCount == 1) "1 book updated" else "$changedCount books updated"

/**
 * What a run that stopped partway reports.
 *
 * ⛔ Names how many were already committed. There is no rollback, so "it failed" without the number
 * leaves the reader unable to tell what state their library is in.
 */
internal fun failedLabel(appliedCount: Int): String =
    if (appliedCount == 1) {
        "Stopped after 1 book. The rest were not changed."
    } else {
        "Stopped after $appliedCount books. The rest were not changed."
    }

/** How many of the selected books could not be loaded, told plainly, or null when all of them were. */
internal fun notLoadedNote(state: BulkEditUiState.Editing): String? {
    val missing = state.requestedCount - state.bookCount
    return when {
        missing <= 0 -> {
            null
        }

        missing == 1 -> {
            "1 of the ${state.requestedCount} books you selected couldn’t be loaded, so it will not be changed."
        }

        else -> {
            "$missing of the ${state.requestedCount} books you selected couldn’t be loaded, " +
                "so they will not be changed."
        }
    }
}

/** The share of the selection an instruction touches, for the proportion bar. */
internal fun proportionOf(
    row: BulkEditPreviewRow,
    bookCount: Int,
): Float = if (bookCount <= 0) 0f else row.affectedCount.toFloat() / bookCount
