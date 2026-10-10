package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.calypsan.listenup.web.motion.heroTarget
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.css.StyleScope
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDivElement

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
 *
 * A cover is always SQUARE, which is why [size] is one number and not two. Audiobook artwork is
 * 1:1 — the server stores and serves it that way — so a portrait frame does not letterbox a cover,
 * it crops a third of it off the sides with `object-fit: cover`, taking the title and the author's
 * name with it. There was a `height` parameter here once, defaulting to [size] and documented as
 * the way to get "the cover's true 2:3 portrait aspect"; that aspect was never true of this app,
 * and five call sites had taken it up. Deleting the parameter is what stops it coming back.
 *
 * [decorative] is for a cover with the book's title printed right beside it — a card, a row, a
 * hero. Its `alt` is then empty and the fallback's title hidden from assistive technology, because
 * naming the book in the picture and again in the text makes a screen reader say it twice.
 *
 * The image loads lazily and decodes off the main thread, so a page of forty covers fetches the
 * ones the reader can see rather than all forty at once. [eager] opts out for the cover that IS the
 * page — Book Detail's hero, Now Playing — where lazy loading would only delay the one image the
 * reader came for: a lazy image waits for layout before it is even requested. The box is sized
 * either way, so neither choice shifts the layout when the image lands.
 *
 * [size] `null` is a fluid cover: square, as wide as its container — a grid tile whose column the
 * grid decides. Its fallback title scales with its own width. [srcset] lets the browser pick a rung by
 * pixel density, which it knows and the page does not. [attrs] is for the caller's placement class
 * and hooks (the library tile's hero-flight origin) on the cover's own box. [overlay] draws on top of
 * the art, inside the box.
 *
 * This is the only cover on web. Library, a contributor's tiles and a series' rows each used to draw
 * their own `<img>` with their own coverless tile — three fallbacks, so the same book with no artwork
 * looked different on every page it appeared on.
 */
@Composable
fun Cover(
    title: String,
    imageUrl: String? = null,
    size: Int? = DEFAULT_COVER_SIZE,
    radius: Int = DEFAULT_COVER_RADIUS,
    heroName: String? = null,
    heroKey: String? = null,
    decorative: Boolean = false,
    eager: Boolean = false,
    srcset: String? = null,
    attrs: (AttrsScope<HTMLDivElement>.() -> Unit)? = null,
    /**
     * Drawn inside the cover's own positioned box, above the art — for a marker that must move with
     * the cover (a card's hover lift) and must never change the box's size (the virtualised grid's
     * row arithmetic). The collection lock is the one user.
     */
    overlay: (@Composable () -> Unit)? = null,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val showImage = imageUrl != null && !failed

    Div(attrs = {
        // The arrival half of a shared-element flight, and the hero the return leg reads. See
        // [heroTarget] — and [com.calypsan.listenup.web.motion.flyHeroInto] for why it is a FLIP.
        heroKey?.let { heroTarget(it) }
        classes("cover")
        style { coverBox(size, radius, heroName, if (showImage) null else title) }
        attrs?.invoke(this)
    }) {
        if (showImage) {
            CoverImage(url = imageUrl, alt = if (decorative) "" else title, eager = eager, srcset = srcset) {
                failed = true
            }
        } else if (size == null || size >= MIN_SIZE_FOR_FALLBACK_TITLE) {
            FallbackTitle(title, size, radius, decorative)
        }
        overlay?.invoke()
    }
}

/**
 * The cover's box: its size (fixed, or fluid and square), its corner, and what it is filled with —
 * a quiet waiting tile under an image still loading, or [fallbackSeed]'s gradient when there is none.
 */
private fun StyleScope.coverBox(
    size: Int?,
    radius: Int,
    heroName: String?,
    fallbackSeed: String?,
) {
    if (size == null) {
        property("width", "100%")
        property("aspect-ratio", "1 / 1")
        // The fallback title is sized against this box's own width (`cqi`), as a fixed cover sizes
        // it against its size.
        property("container-type", "inline-size")
    } else {
        property("width", "${size}px")
        property("height", "${size}px")
    }
    property("border-radius", "${radius}px")
    property("overflow", "hidden")
    property("flex-shrink", "0")
    property("position", "relative")
    // The shared-element handle. When the grid tile the reader tapped carries the same name, the
    // browser interpolates between the two boxes instead of crossfading the pages — the cover
    // appears to fly from the grid into this hero, Flutter-Hero style.
    // ⛔ A `view-transition-name` must be unique at any instant, which is why only ONE grid tile is
    // ever named: see `HERO_COVER` in the library grid.
    heroName?.let { property("view-transition-name", it) }
    property(
        "background",
        if (fallbackSeed == null) {
            // What a lazy cover shows while it is still on its way: a waiting tile, not a hole.
            "var(--surface-2)"
        } else {
            tintGradient(
                seed = fallbackSeed,
                angleDegrees = COVER_GRADIENT_ANGLE,
                firstSaturation = COVER_FIRST_SATURATION,
                firstLightness = COVER_FIRST_LIGHTNESS,
                secondSaturation = COVER_SECOND_SATURATION,
                secondLightness = COVER_SECOND_LIGHTNESS,
            )
        },
    )
}

/** The title set inside a coverless tile, sized to the tile. See [Cover] on `decorative`. */
@Composable
private fun FallbackTitle(
    title: String,
    size: Int?,
    radius: Int,
    decorative: Boolean,
) {
    Span(attrs = {
        if (decorative) attr("aria-hidden", "true")
        style {
            property("position", "absolute")
            property("inset", "0")
            property("display", "flex")
            property("align-items", "flex-end")
            property("padding", "${radius / 2 + 4}px")
            property("color", "rgba(255,255,255,0.92)")
            property(
                "font-size",
                if (size == null) {
                    "clamp(${MIN_FALLBACK_TEXT}px, ${FLUID_FALLBACK_TEXT_CQI}cqi, ${MAX_FALLBACK_TEXT}px)"
                } else {
                    "${(size / 9).coerceIn(MIN_FALLBACK_TEXT, MAX_FALLBACK_TEXT)}px"
                },
            )
            property("font-weight", "800")
            property("letter-spacing", "-0.02em")
            property("line-height", "1.15")
            property("text-wrap", "pretty")
        }
    }) {
        Text(title)
    }
}

/** The artwork itself, filling the cover's box. See [Cover] on [eager]. */
@Composable
private fun CoverImage(
    url: String,
    alt: String,
    eager: Boolean,
    srcset: String?,
    onFailed: () -> Unit,
) {
    Img(
        src = url,
        alt = alt,
        attrs = {
            srcset?.let { attr("srcset", it) }
            attr("loading", if (eager) "eager" else "lazy")
            attr("decoding", if (eager) "auto" else "async")
            style {
                property("width", "100%")
                property("height", "100%")
                property("object-fit", "cover")
                property("display", "block")
            }
            // A broken cover must not leave a blank tile: fall back to the generated one.
            // Compose HTML has no `onError` helper, so the listener is attached by name.
            addEventListener("error") { onFailed() }
        },
    )
}

private const val DEFAULT_COVER_SIZE = 96

private const val DEFAULT_COVER_RADIUS = 14

// Saturation and lightness stay in a narrow, muted band so no generated cover fights the coral
// action colour or looks out of place beside real artwork. See [tintGradient] for the hue math.
private const val COVER_GRADIENT_ANGLE = 160

private const val COVER_FIRST_SATURATION = 28

private const val COVER_FIRST_LIGHTNESS = 34

private const val COVER_SECOND_SATURATION = 32

private const val COVER_SECOND_LIGHTNESS = 14

/**
 * Below this, the generated cover is a bare gradient.
 *
 * A tile this small cannot hold two lines of the smallest legible text once its padding is taken
 * out, so a title set inside one is clipped mid-word rather than read. Both call sites under it —
 * a search hit and a Discover listener row — already print the book's name beside the tile, so
 * nothing is lost by leaving it off; a cropped word next to an intact one is purely noise.
 */
private const val MIN_SIZE_FOR_FALLBACK_TITLE = 72

private const val MIN_FALLBACK_TEXT = 10

private const val MAX_FALLBACK_TEXT = 22

/** A fluid cover's fallback title, as a share of its width: the same one-ninth a fixed cover uses. */
private const val FLUID_FALLBACK_TEXT_CQI = 11

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
