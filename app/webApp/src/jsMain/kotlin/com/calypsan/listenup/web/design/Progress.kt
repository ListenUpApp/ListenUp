package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Listening progress: a bar, and the remaining time beside it.
 *
 * The comp gives this a fixed pixel width because artboards are fixed-size frames. That is a
 * canvas artifact — here the bar flexes and only the label is intrinsic, so the same component
 * works in a 300px rail and a full-width header without a second variant.
 *
 * [percent] is clamped rather than trusted: a stored position slightly past a re-encoded file's
 * duration is a real occurrence, and an unclamped bar overflows its track when it happens.
 */
@Composable
fun ProgressLine(
    percent: Int,
    remaining: String,
) {
    val clamped = percent.coerceIn(0, PERCENT_MAX)
    Div(attrs = {
        style {
            property("display", "flex")
            property("align-items", "center")
            property("gap", "11px")
            property("width", "100%")
        }
        attr("role", "progressbar")
        attr("aria-valuenow", clamped.toString())
        attr("aria-valuemin", "0")
        attr("aria-valuemax", PERCENT_MAX.toString())
    }) {
        Span(attrs = {
            style {
                property("flex", "1")
                property("height", "6px")
                property("border-radius", "99px")
                property("background", "var(--surface-3)")
                property("min-width", "32px")
            }
        }) {
            Span(attrs = {
                style {
                    property("display", "block")
                    property("width", "$clamped%")
                    property("height", "100%")
                    property("border-radius", "99px")
                    property("background", "var(--coral)")
                }
            }) {}
        }
        Span(attrs = {
            classes("mono")
            style {
                property("font-size", "12px")
                property("color", "var(--ink-2)")
                property("white-space", "nowrap")
                property("flex-shrink", "0")
            }
        }) {
            Text("$clamped% · $remaining")
        }
    }
}

private const val PERCENT_MAX = 100
