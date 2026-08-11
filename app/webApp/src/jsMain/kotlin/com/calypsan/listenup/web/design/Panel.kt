package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * A titled card.
 *
 * Book Detail is almost entirely panels — description, up-next, files, details, tags, readers —
 * so this is the layout unit of the overview tab.
 *
 * [flush] drops the body padding, which is what a panel wrapping a [DataTable] needs: the table
 * draws its own edges, and padding around it would leave the rows floating inside a border.
 */
@Composable
fun Panel(
    title: String? = null,
    flush: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Section(attrs = {
        style {
            property("background", "var(--surface)")
            property("border", "1px solid var(--line)")
            property("border-radius", "var(--rad)")
            property("box-shadow", "var(--shadow-card)")
            property("overflow", "hidden")
        }
    }) {
        if (title != null) {
            Header(attrs = {
                style {
                    property("display", "flex")
                    property("align-items", "center")
                    property("gap", "12px")
                    property("padding", "0 16px")
                    property("height", "46px")
                    property("border-bottom", "1px solid var(--line-2)")
                }
            }) {
                H3(attrs = {
                    style {
                        property("margin", "0")
                        property("font-size", "14px")
                        property("font-weight", "700")
                        property("letter-spacing", "-0.01em")
                        property("color", "var(--ink)")
                    }
                }) {
                    Text(title)
                }
                Span(attrs = { style { property("flex", "1") } }) {}
                trailing?.invoke()
            }
        }
        Div(attrs = {
            style { property("padding", if (flush) "0" else "18px") }
        }) {
            content()
        }
    }
}
