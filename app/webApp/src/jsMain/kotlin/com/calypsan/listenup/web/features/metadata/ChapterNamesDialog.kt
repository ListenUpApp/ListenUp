package com.calypsan.listenup.web.features.metadata

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Review the names before they land.
 *
 * ⛔ Names only — the timings never move. A chapter's start is where the audio actually changes,
 * and Audible's idea of it belongs to a different file; taking their labels is useful, taking
 * their clock would put every boundary in the wrong place.
 *
 * Every row is its own decision, because a scrape is usually right about most chapters and wrong
 * about the ones with unusual names, which are exactly the ones a reader looks at.
 */
@Composable
internal fun ChapterNamesDialog(
    available: ChapterSuggestion.Available,
    onToggleChapter: (Int) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalDialog(open = true, title = "Review chapter names", onDismiss = onDismiss) {
        P(attrs = { classes("dlg-p") }) {
            Text("Audible names replace your current chapter labels. Timings stay the same.")
        }

        available.applyError?.let {
            P(attrs = {
                classes("mdx-err")
                attr("role", "alert")
            }) { Text(it) }
        }

        P(attrs = { classes("mdx-chsel") }) { Text(selectionLabel(available)) }

        Div(attrs = { classes("mdx-chrows") }) {
            available.rows.forEach { row ->
                Div(attrs = { classes("mdx-chrow") }) {
                    CheckboxField(
                        label = row.suggestedName,
                        checked = row.ordinal in available.selectedOrdinals,
                        onChange = { onToggleChapter(row.ordinal) },
                    )
                    // The current name, so the reader can see what is being replaced rather than
                    // only what it is being replaced with.
                    Span(attrs = { classes("mdx-chwas") }) { Text(row.currentName) }
                }
            }
        }

        Div(attrs = { classes("dlg-actions") }) {
            Button(attrs = {
                classes("btn")
                attr("type", "button")
                onClick { onDismiss() }
            }) { Text("Cancel") }
            Button(attrs = {
                classes("btn-c")
                attr("type", "button")
                disabledWhen(available.isApplying || available.selectedOrdinals.isEmpty())
                onClick { onApply() }
            }) { Text(if (available.isApplying) "Applying…" else "Apply chapter names") }
        }
    }
}

/** "All 34 selected" reads better than "34 of 34" when it is all of them. */
internal fun selectionLabel(available: ChapterSuggestion.Available): String {
    val selected = available.selectedOrdinals.size
    val total = available.rows.size
    return if (selected == total) "All $total selected" else "$selected of $total selected"
}
