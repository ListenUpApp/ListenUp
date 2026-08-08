package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Text

/**
 * The root of the ListenUp web body.
 *
 * `luw` plus a direction class is the contract `web.css` is written against: every token and
 * component class is scoped under `.luw`, and the direction picks the voice.
 *
 * `dir-a` — "Paper" — is the house voice. `Web/Foundations.html`, the canonical pattern sheet,
 * renders every surface in it and does not even load the Console face; `dir-b` appeared only as
 * a local experiment on the Book Detail comp. The voice is not the same axis as the layout: we
 * take the Workbench LAYOUT (tabbed panes, tables first) in the Paper VOICE, which `web.css`
 * supports directly — the direction class only swaps the type face plus a few radius and label
 * modifiers.
 *
 * Deliberately thin for now. Real structure arrives with the component kit; this exists so the
 * rendering path is proven before anything is built on top of it.
 */
@Composable
fun WebAppRoot() {
    Div(attrs = {
        classes("luw", "dir-a")
    }) {
        H1 { Text("ListenUp") }
    }
}
