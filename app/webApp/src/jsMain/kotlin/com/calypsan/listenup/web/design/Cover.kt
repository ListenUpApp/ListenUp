package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.abs

/**
 * A book cover.
 *
 * The comp draws every cover as a hand-picked gradient with the title set inside it, because the
 * design project has no real artwork. That is a canvas convenience, not a design decision: this
 * app has real covers behind `/api/v1/books/{id}/cover`. So the real component loads the image
 * and keeps the gradient as the **fallback** — for books with no artwork yet, and for the moment
 * a request fails.
 *
 * The fallback colour is derived from the title rather than random, so a given book keeps the
 * same cover across sessions, devices and reloads. A cover that changes on refresh reads as a
 * bug even when nothing is wrong.
 */
@Composable
fun Cover(
    title: String,
    imageUrl: String? = null,
    size: Int = DEFAULT_COVER_SIZE,
    radius: Int = DEFAULT_COVER_RADIUS,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val showImage = imageUrl != null && !failed

    Div(attrs = {
        style {
            property("width", "${size}px")
            property("height", "${size}px")
            property("border-radius", "${radius}px")
            property("overflow", "hidden")
            property("flex-shrink", "0")
            property("position", "relative")
            if (!showImage) property("background", gradientFor(title))
        }
    }) {
        if (showImage) {
            Img(
                src = imageUrl,
                alt = title,
                attrs = {
                    style {
                        property("width", "100%")
                        property("height", "100%")
                        property("object-fit", "cover")
                        property("display", "block")
                    }
                    // A broken cover must not leave a blank tile: fall back to the generated one.
                    // Compose HTML has no `onError` helper, so the listener is attached by name.
                    addEventListener("error") { failed = true }
                },
            )
        } else {
            Span(attrs = {
                style {
                    property("position", "absolute")
                    property("inset", "0")
                    property("display", "flex")
                    property("align-items", "flex-end")
                    property("padding", "${radius / 2 + 4}px")
                    property("color", "rgba(255,255,255,0.92)")
                    property("font-size", "${(size / 9).coerceIn(MIN_FALLBACK_TEXT, MAX_FALLBACK_TEXT)}px")
                    property("font-weight", "800")
                    property("letter-spacing", "-0.02em")
                    property("line-height", "1.15")
                    property("text-wrap", "pretty")
                }
            }) {
                Text(title)
            }
        }
    }
}

/**
 * A stable, title-derived gradient.
 *
 * Hue comes from the title's hash so it is deterministic; saturation and lightness stay in a
 * narrow, muted band so no generated cover fights the coral action colour or looks out of place
 * beside real artwork.
 */
private fun gradientFor(title: String): String {
    val hue = abs(title.hashCode()) % HUE_RANGE
    val second = (hue + HUE_SPREAD) % HUE_RANGE
    return "linear-gradient(160deg, hsl($hue 28% 34%), hsl($second 32% 14%))"
}

private const val DEFAULT_COVER_SIZE = 96

private const val DEFAULT_COVER_RADIUS = 14

private const val HUE_RANGE = 360

private const val HUE_SPREAD = 24

private const val MIN_FALLBACK_TEXT = 10

private const val MAX_FALLBACK_TEXT = 22
