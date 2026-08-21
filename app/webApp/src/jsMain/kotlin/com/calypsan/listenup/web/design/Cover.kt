package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.calypsan.listenup.web.motion.CoverSurface
import com.calypsan.listenup.web.motion.flyHeroInto
import com.calypsan.listenup.web.motion.releaseHero
import com.calypsan.listenup.web.motion.trackHero
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
    heroName: String? = null,
    heroBookId: String? = null,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val showImage = imageUrl != null && !failed

    Div(attrs = {
        // The arrival half of the cover's flight. `ref` fires when this node is attached, which is
        // exactly when its final geometry is known and the origin recorded at click time can be
        // animated from. See [flyHeroInto] for why this is a FLIP rather than a View Transition.
        if (heroBookId != null) {
            ref { element ->
                flyHeroInto(heroBookId, CoverSurface.HERO, element)
                // Tracked, not measured: the return leg's origin is read at route-change time, while
                // this node is still laid out. See [captureHeroOriginBeforeRouteChange].
                trackHero(heroBookId, element)
                onDispose { releaseHero(element) }
            }
        }
        style {
            property("width", "${size}px")
            property("height", "${size}px")
            property("border-radius", "${radius}px")
            property("overflow", "hidden")
            property("flex-shrink", "0")
            property("position", "relative")
            // The shared-element handle. When the grid tile the reader tapped carries the same
            // name, the browser interpolates between the two boxes instead of crossfading the
            // pages — the cover appears to fly from the grid into this hero, Flutter-Hero style.
            // ⛔ A `view-transition-name` must be unique at any instant, which is why only ONE
            // grid tile is ever named: see `HERO_COVER` in the library grid.
            heroName?.let { property("view-transition-name", it) }
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

/**
 * A same-origin relative URL, authenticated by the cookie the browser already holds.
 *
 * Relative rather than absolute on purpose: the server serves this bundle in the normal deployment,
 * and a cookie cannot cross origins anyway — so an absolute URL pointing at a different server would
 * produce an unauthenticated request rather than a working image.
 *
 * **`w`** asks for a rung of the server's derivative ladder. The server rounds it up to a rung it
 * has, and serves the full-size original for anything it cannot derive — so a width is a request,
 * never a demand, and a cover that declines is no worse off than before the parameter existed.
 *
 * ⛔ **`v` is the artwork's content hash, and it is load-bearing.** Covers are served
 * `immutable` for a year, so the URL is the only thing that can tell a browser the artwork changed;
 * without it, a re-covered book stays stale on web until the cache expires. Android and desktop
 * have always done this — web had not, which was a live bug rather than a missing nicety.
 */
internal fun coverUrl(
    bookId: String,
    coverHash: String?,
    width: Int? = null,
): String {
    val query =
        listOfNotNull(
            width?.let { "w=$it" },
            coverHash?.let { "v=$it" },
        ).joinToString("&")
    return "/api/v1/books/$bookId/cover" + if (query.isEmpty()) "" else "?$query"
}
