package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div

/**
 * One titled block of form rows.
 *
 * The panel and the row rhythm always travel together — a section that forgot the rhythm would
 * render its fields flush against each other — so they are one thing rather than two lines to
 * remember at every call site.
 *
 * Lifted out of Book Edit when Edit Profile became the second form built from these: two private
 * copies of the same two lines is how the rhythm drifts between one form and the next.
 */
@Composable
fun FormSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Panel(title = title) {
        Div(attrs = { classes("edit-form") }) { content() }
    }
}
