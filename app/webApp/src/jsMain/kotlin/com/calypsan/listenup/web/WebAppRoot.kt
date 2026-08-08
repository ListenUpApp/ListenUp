package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Text

/**
 * The root of the ListenUp web body.
 *
 * `luw` plus a direction class is the contract `web.css` is written against: every token and
 * component class is scoped under `.luw`, and the direction picks the type voice. `dir-b` is the
 * "Console" voice — the workbench direction the Book Detail comp argues for, on the grounds that
 * the web client is a record you operate on rather than a page you read.
 *
 * Deliberately thin for now. Real structure arrives with the component kit; this exists so the
 * rendering path is proven before anything is built on top of it.
 */
@Composable
fun WebAppRoot() {
    Div(attrs = {
        classes("luw", "dir-b")
    }) {
        H1 { Text("ListenUp") }
    }
}
