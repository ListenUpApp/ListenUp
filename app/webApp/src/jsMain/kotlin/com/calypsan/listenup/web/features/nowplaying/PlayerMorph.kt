package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.player.MorphEnd
import com.calypsan.listenup.client.presentation.player.MorphRect
import com.calypsan.listenup.client.presentation.player.PlayerMorph
import com.calypsan.listenup.client.presentation.player.SpringParameters
import com.calypsan.listenup.client.presentation.player.progressVelocity
import com.calypsan.listenup.client.presentation.player.settleTarget
import com.calypsan.listenup.web.motion.Keyframe
import com.calypsan.listenup.web.motion.MotionToken
import com.calypsan.listenup.web.motion.animateComposited
import com.calypsan.listenup.web.motion.prefersLessMotion
import com.calypsan.listenup.web.nav.HOME_KEY
import com.calypsan.listenup.web.nav.PLAYING_KEY
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.savedScrollFor
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The mini player morph on the web (canvas "Mini player drag & morph": `MorphWeb`, `GestureWeb`, `Reduce`, `Edges`).
 *
 * Below 760px the bar grows into Now Playing. Its cover, title, play button and line are measured once, the page's
 * twins are measured once at rest, and the offsets between them are written once as custom properties. From then on a
 * frame writes one number, `--mp-p`, on the shell: an animation (the shared spring, as a CSS `linear()`) or a finger.
 * `05-patterns.css` (PLAYER MORPH) computes every transform and opacity from it, so nothing recomposes and nothing
 * lays out per frame.
 *
 * Now Playing is drawn as a layer over the page it rose from while the morph runs ([NowPlayingSlot]), so the page
 * underneath stays put under the scrim, and the URL and the motion never disagree: `/playing` is pushed as the page
 * mounts and history goes back as it leaves (the leaving page is held as the layer until progress reaches 0).
 *
 * From 760px, and for a deep link, there is no morph: the page fades through, or simply appears. Under reduced motion
 * nothing travels: Now Playing crossfades over the bar for 150 ms (the one motion that runs then, `Motion.kt`).
 */

/**
 * Whether the bar grows into Now Playing: below 760px ([isPhone], where the page is the whole screen with its own
 * transport), from a bar on screen ([barMounted]), and opened from inside the app ([arrivedInApp]; a deep link has no
 * bar to grow from). Pure.
 */
internal fun morphApplies(
    isPhone: Boolean,
    barMounted: Boolean,
    arrivedInApp: Boolean,
): Boolean = isPhone && barMounted && arrivedInApp

/** The four parts that fly from the bar to Now Playing, and the custom properties their offsets are written under. */
internal enum class MorphPart(
    val stem: String,
    val barSelector: String,
    val pageSelector: String,
) {
    Cover("cover", ".tport-cover", ".np-cover"),
    Title("title", ".tport-t", ".np-title"),
    Play("play", ".tport-b", ".np-play"),
    Line("line", ".tport-line", ".np-scrub"),
    ;

    /** Set to 0 on the shell while this part's twin flies, so the bar's own copy steps aside. */
    val barFlag: String get() = "--mp-bar-$stem"
}

/**
 * The one morph per window: what is running, what is under it, and the numbers it writes. Its state (whether a morph
 * runs, whether it crossfades, the page underneath) changes at a morph's start and end only, never per frame, so the
 * shell recomposes twice per morph and the page and the bar not at all.
 */
class MorphController {
    /** True while a morph is on screen: the shell keeps its tabs and bar, and Now Playing is a layer. */
    var isRunning by mutableStateOf(false)
        private set

    /** True while the running morph is the reduced-motion crossfade. */
    var isCrossfade by mutableStateOf(false)
        private set

    /** Bumped at every morph's start, so Now Playing's slot measures the frame it lands in. */
    var generation by mutableStateOf(0)
        private set

    /** The page drawn under Now Playing while the URL is still `/playing`: the one it rose from, or will close onto. */
    private var underRoute by mutableStateOf<Route?>(null)

    /** The route the scroll memory sees while an open runs: the page underneath must not jump to its top. */
    private var frozenScrollRoute by mutableStateOf<Route?>(null)

    private var shell: HTMLElement? = null
    private var router: Router? = null
    private var shownRoute: Route? = null
    private var barRects: Map<MorphPart, MorphRect> = emptyMap()
    private var travelPx: Double = 0.0
    private var pending: Pending? = null
    private var fadeThroughPending = false
    private var animation: dynamic = null
    private var quietArrivalPage: String? = null

    /** The page `/playing` was opened from inside the app, for a drag down to show underneath; null for a deep link. */
    private var returnRoute: Route? = null

    /** Set while a finger's open is being started, so the open waits for the finger rather than playing. */
    private var openingByDrag = false

    private var openFromDrag: (HTMLElement) -> Unit = {}
    private var closeFromDrag: () -> Unit = {}

    /** Where the running morph is heading. */
    private var heading: MorphEnd = MorphEnd.NowPlaying

    /** The classes the shell wears while a morph runs. */
    val shellClasses: List<String>
        get() =
            buildList {
                if (isRunning) add(SHELL_MORPHING)
                if (isRunning && isCrossfade) add(SHELL_CROSSFADE)
            }

    /** What the page region shows for [route]: the page under Now Playing while a morph runs over `/playing`. */
    fun contentRoute(route: Route): Route = if (isRunning && route.page() == PLAYING_KEY) underRoute ?: route else route

    /** What the scroll memory is told is showing: held still while an open runs over the page it rose from. */
    fun scrollRoute(route: Route): Route = frozenScrollRoute ?: route

    /**
     * Takes the [shell] this morph draws on and the [router] whose history it follows: writes the timeline once,
     * listens for Back and Forward, and lets a finger drag the bar ([MorphDrag]). A drag opens and closes the page the
     * way a click and Collapse do: [openFromDrag] with the bar's book as the opener, and [closeFromDrag]. Returns the
     * call that lets go.
     */
    fun attach(
        shell: HTMLElement,
        router: Router,
        openFromDrag: (opener: HTMLElement) -> Unit,
        closeFromDrag: () -> Unit,
    ): () -> Unit {
        this.shell = shell
        this.router = router
        this.openFromDrag = openFromDrag
        this.closeFromDrag = closeFromDrag
        writeMorphTimeline(shell)
        // After the router's own listener (it was made first), so the route has already moved when this runs.
        val onPopstate: (Event) -> Unit = { onHistoryStep(router.current) }
        window.addEventListener("popstate", onPopstate)
        val releaseDrag = MorphDrag(this).install(shell)
        return {
            releaseDrag()
            window.removeEventListener("popstate", onPopstate)
            animation?.cancel()
            animation = null
            this.shell = null
            this.router = null
        }
    }

    /** The route the shell last drew: what a history step moved away from. */
    fun observe(route: Route) {
        shownRoute = route
    }

    /** Called just before `/playing` is pushed from [from] (a click, P, Expand): measure the bar while it is drawn. */
    fun beforeOpen(from: Route) {
        returnRoute = from
        open(from, arrivedInApp = true)
    }

    /** Whether a drag can start here and now: below 760px, with no morph already moving. */
    internal fun acceptsDrag(): Boolean = !isRunning && shell?.let(::isPhone) == true

    /** Whether the page on show is Now Playing, which a drag closes; anything else, the bar's drag opens it. */
    internal fun isOnPlaying(): Boolean = router?.current?.page() == PLAYING_KEY

    /** The drag's full travel: the bar's top edge, last measured. */
    internal val travel: Double get() = travelPx

    /** A finger has pulled the bar's book [opener] past the slop: push `/playing` and hold the morph for the finger. */
    internal fun beginDragOpen(opener: HTMLElement): Boolean {
        openingByDrag = true
        try {
            openFromDrag(opener)
        } finally {
            openingByDrag = false
        }
        return isRunning
    }

    /** A finger has pulled Now Playing down past the slop: hold it as a layer over the page it would close onto. */
    internal fun beginDragClose(): Boolean {
        val shell = shell ?: return false
        val bar = shell.querySelector(BAR_SELECTOR) as? HTMLElement
        if (!morphApplies(isPhone = isPhone(shell), barMounted = bar != null, arrivedInApp = true)) return false
        shell.style.setProperty(MORPH_PROGRESS, "1")
        heading = MorphEnd.Bar
        underRoute = returnRoute ?: Routes.home
        frozenScrollRoute = null
        barRects = emptyMap()
        start(Pending.Hold(restoresUnderScroll = true))
        return true
    }

    /** The finger is at [progress]: the one write a drag makes per move. */
    internal fun dragTo(progress: Float) {
        shell?.style?.setProperty(MORPH_PROGRESS, progress.toString())
    }

    /**
     * The finger let go at [velocityPxPerSec] (positive downward): the shared rule picks the end (velocity first, then
     * the halfway line), history follows it, and the default spatial spring carries the release's speed there.
     */
    internal fun release(velocityPxPerSec: Float) {
        val shell = shell ?: return
        val end = settleTarget(currentProgress(shell), velocityPxPerSec)
        val velocity = progressVelocity(velocityPxPerSec, travelPx.toFloat())
        if (end == MorphEnd.Bar) {
            // The URL first, as Collapse does: back into history, or Home for a deep link.
            heading = MorphEnd.Bar
            closeFromDrag()
        }
        settle(end, velocity)
    }

    /**
     * Whether the page arrival stands down for a change to [page]: always while a morph runs (the morph is the
     * arrival), and once for the page a morph has just landed on.
     */
    fun arrivalStandsDown(page: String): Boolean {
        if (isRunning) return true
        // Spent by the first change it is asked about, so it can never silence a later visit to the same page.
        val quiet = quietArrivalPage == page
        quietArrivalPage = null
        return quiet
    }

    /**
     * Now Playing's [slot] has been laid out for this morph: measure the page's parts at rest, write the offsets once,
     * and play.
     */
    internal fun onApplied(slot: HTMLElement) {
        if (fadeThroughPending) {
            fadeThroughPending = false
            (slot.querySelector(".np-page") as? HTMLElement)?.let(::fadeThrough)
            return
        }
        val todo = pending ?: return
        pending = null
        val shell = shell ?: return
        val bar = shell.querySelector(BAR_SELECTOR) as? HTMLElement
        // Nothing to fly (the book was closed as the page left, say): the morph simply ends where it was going.
        if (slot.querySelector(".np-morph") == null || bar == null) {
            land(heading)
            return
        }
        if (barRects.isEmpty()) measureBar(shell, bar)
        if (!isCrossfade) writeFlights(shell, slot)
        when (todo) {
            is Pending.Settle -> {
                animateTo(todo.target, todo.spring, todo.velocity)
            }

            is Pending.Hold -> {
                // A drag down shows the page it would close onto, where the reader left it.
                if (todo.restoresUnderScroll) {
                    val under = underRoute
                    (shell.querySelector(".shell-main") as? HTMLElement)?.scrollTop =
                        under?.let(::savedScrollFor) ?: 0.0
                }
            }
        }
    }

    private fun onHistoryStep(to: Route) {
        val from = shownRoute ?: return
        val wasPlaying = from.page() == PLAYING_KEY
        val isPlaying = to.page() == PLAYING_KEY
        when {
            // Back while the bar is still growing: turn round from wherever it has got to.
            isRunning && heading == MorphEnd.NowPlaying && !isPlaying -> {
                settle(MorphEnd.Bar)
            }

            isRunning -> {
                Unit
            }

            !wasPlaying && isPlaying -> {
                // Forward counts as a deep link for closing (PlayingReturn), so a drag down shows Home underneath.
                returnRoute = null
                open(from, arrivedInApp = true)
            }

            wasPlaying && !isPlaying -> {
                close()
            }
        }
    }

    /**
     * History has just left `/playing` (Collapse, Esc and P go back; so does the browser): hold the page as a layer
     * over the destination and play it back into the bar, which is measured once it is drawn again.
     */
    private fun close() {
        val shell = shell ?: return
        val bar = shell.querySelector(BAR_SELECTOR) as? HTMLElement
        if (!morphApplies(isPhone = isPhone(shell), barMounted = bar != null, arrivedInApp = true)) return
        shell.style.setProperty(MORPH_PROGRESS, "1")
        heading = MorphEnd.Bar
        underRoute = null
        frozenScrollRoute = null
        barRects = emptyMap()
        start(Pending.Settle(target = 0f, spring = PlayerMorph.SETTLE_SPRING, velocity = 0f))
    }

    /** Heads for [end] on the default spatial spring: now if the morph is playing, or as soon as it is measured. */
    private fun settle(
        end: MorphEnd,
        velocity: Float = 0f,
    ) {
        heading = end
        val target = if (end == MorphEnd.NowPlaying) 1f else 0f
        if (pending != null) {
            // Not measured yet (a flick quicker than a frame): play as soon as it is.
            pending = Pending.Settle(target, PlayerMorph.SETTLE_SPRING, velocity)
        } else {
            animateTo(target, PlayerMorph.SETTLE_SPRING, velocity)
        }
    }

    private fun open(
        from: Route,
        arrivedInApp: Boolean,
    ) {
        val shell = shell ?: return
        if (isRunning) return
        if (!isPhone(shell)) {
            fadeThroughPending = true
            return
        }
        val bar = shell.querySelector(BAR_SELECTOR) as? HTMLElement
        if (!morphApplies(isPhone = true, barMounted = bar != null, arrivedInApp = arrivedInApp) || bar == null) return
        measureBar(shell, bar)
        shell.style.setProperty(MORPH_PROGRESS, "0")
        heading = MorphEnd.NowPlaying
        underRoute = from
        frozenScrollRoute = from
        start(
            if (openingByDrag) {
                Pending.Hold(restoresUnderScroll = false)
            } else {
                Pending.Settle(target = 1f, spring = PlayerMorph.TAP_SPRING, velocity = 0f)
            },
        )
    }

    private fun start(todo: Pending) {
        pending = todo
        isCrossfade = prefersLessMotion()
        isRunning = true
        generation++
    }

    /** Plays progress from wherever it is to [target] on [spring], launched at [velocity] progress per second. */
    private fun animateTo(
        target: Float,
        spring: SpringParameters,
        velocity: Float,
    ) {
        val shell = shell ?: return
        val from = currentProgress(shell)
        animation?.cancel()
        animation = null
        val distance = target - from
        if (abs(distance) < SETTLED) {
            shell.style.setProperty(MORPH_PROGRESS, target.toString())
            land(if (target >= 1f) MorphEnd.NowPlaying else MorphEnd.Bar)
            return
        }
        val options = js("{}")
        if (isCrossfade) {
            options.duration = (CROSSFADE_MS * abs(distance)).roundToInt().coerceAtLeast(1)
            options.easing = "linear"
        } else {
            val easing = SpringEasing.of(spring, velocity / distance)
            options.duration = easing.durationMs
            options.easing = easing.easing
        }
        // The end value is committed first, so the moment the animation ends the shell already rests there.
        shell.style.setProperty(MORPH_PROGRESS, target.toString())
        val started = shell.asDynamic().animate(arrayOf(progressFrame(from), progressFrame(target)), options)
        animation = started
        started.onfinish = { _: dynamic ->
            if (animation === started) {
                animation = null
                land(if (target >= 1f) MorphEnd.NowPlaying else MorphEnd.Bar)
            }
        }
    }

    /** The morph is over at [end]: the shell drops its morph classes and Now Playing is a page again, or gone. */
    private fun land(end: MorphEnd) {
        isRunning = false
        isCrossfade = false
        underRoute = null
        frozenScrollRoute = null
        barRects = emptyMap()
        pending = null
        // Landing on Now Playing swaps the page underneath for `/playing` in the page region: that is not an arrival.
        quietArrivalPage = if (end == MorphEnd.NowPlaying) PLAYING_KEY else null
        heading = end
        if (end == MorphEnd.Bar) returnFocusToBar()
        // Back on Now Playing after a drag down: the region scrolled the page underneath, and now holds Now Playing.
        if (end == MorphEnd.NowPlaying) (shell?.querySelector(".shell-main") as? HTMLElement)?.scrollTop = 0.0
    }

    /**
     * Never stranded (canvas `Edges`): closed back into the bar, focus is on the bar's book, the control the page grew
     * out of. A control in the bar that already has it (the one that opened the page, given back by PlayingReturn)
     * keeps it; anywhere else (nothing, the document, the page that just left, the destination's heading) it moves.
     */
    private fun returnFocusToBar() {
        val shell = shell ?: return
        val focused = shell.ownerDocument?.activeElement
        if (focused?.closest(BAR_SELECTOR) == null) (shell.querySelector(".tport-body") as? HTMLElement)?.focus()
    }

    /** Measures the bar where it rests: at progress 0, since mid-morph it rides the sheet. */
    private fun measureBar(
        shell: HTMLElement,
        bar: HTMLElement,
    ) {
        val held = shell.style.getPropertyValue(MORPH_PROGRESS)
        shell.style.setProperty(MORPH_PROGRESS, "0")
        travelPx = bar.getBoundingClientRect().top
        barRects =
            MorphPart.entries
                .mapNotNull { part ->
                    (shell.querySelector(part.barSelector) as? HTMLElement)?.let { part to it.morphRect() }
                }.toMap()
        shell.style.setProperty(MORPH_PROGRESS, held)
        shell.style.setProperty(MORPH_TRAVEL, px(travelPx))
    }

    /** Measures the page's parts at rest (progress 1) and writes each part's offsets from the bar's, once. */
    private fun writeFlights(
        shell: HTMLElement,
        slot: HTMLElement,
    ) {
        val held = shell.style.getPropertyValue(MORPH_PROGRESS)
        shell.style.setProperty(MORPH_PROGRESS, "1")
        MorphPart.entries.forEach { part ->
            val from = barRects[part]
            val element = slot.querySelector(".np-morph ${part.pageSelector}") as? HTMLElement
            if (from == null || element == null) {
                shell.style.removeProperty(part.barFlag)
                FLIGHT_VARS.forEach { shell.style.removeProperty("--mp-${part.stem}-$it") }
            } else {
                writeFlight(shell, part, from, element)
            }
        }
        shell.style.setProperty(MORPH_PROGRESS, held)
    }

    private fun writeFlight(
        shell: HTMLElement,
        part: MorphPart,
        from: MorphRect,
        element: HTMLElement,
    ) {
        val box = element.morphRect()
        // The title flies by its words, not its full-width block: their top-left is the point that travels, and the
        // block scales about it by the ratio of the two type sizes.
        val target =
            if (part == MorphPart.Title) {
                (element.querySelector(".np-title-t") as? HTMLElement)?.morphRect() ?: box
            } else {
                box
            }
        val scale =
            if (part == MorphPart.Title) {
                fontSizeOf(shell, MorphPart.Title.barSelector) / fontSizeOf(element)
            } else {
                from.width / target.width
            }
        val stem = "--mp-${part.stem}"
        shell.style.setProperty("$stem-dx", px(from.left - target.left))
        shell.style.setProperty("$stem-dy", px(from.top - target.top))
        shell.style.setProperty("$stem-ox", px(target.left - box.left))
        shell.style.setProperty("$stem-oy", px(target.top - box.top))
        if (part == MorphPart.Line) {
            shell.style.setProperty("$stem-sx", ratio(from.width / target.width))
            shell.style.setProperty("$stem-sy", ratio(from.height / target.height))
        } else {
            shell.style.setProperty("$stem-s", ratio(scale))
        }
        shell.style.setProperty(part.barFlag, "0")
    }

    private sealed interface Pending {
        /** Settle on [target] (0 or 1) on [spring], launched at [velocity] progress per second. */
        class Settle(
            val target: Float,
            val spring: SpringParameters,
            val velocity: Float,
        ) : Pending

        /** A finger has the morph: measure, then follow it. [restoresUnderScroll] for a drag down over a page. */
        class Hold(
            val restoresUnderScroll: Boolean,
        ) : Pending
    }
}

/**
 * Where Now Playing is drawn: in the page region at `/playing`, and as a layer over the page underneath while a morph
 * runs (the page it rose from, or the one it closes onto). One place in the composition for both, so starting or
 * landing a morph never remounts the page.
 */
@Composable
internal fun NowPlayingSlot(
    morph: MorphController,
    router: Router,
    playback: PlaybackSession,
    playingReturn: PlayingReturn,
    onPlayingPage: Boolean,
) {
    if (!onPlayingPage && !morph.isRunning) return
    val slot = remember { SlotElement() }
    Div(attrs = {
        classes("np-slot")
        if (morph.isRunning) classes("np-layer")
        ref { element ->
            slot.element = element
            onDispose { slot.element = null }
        }
    }) {
        NowPlayingRoute(router = router, playback = playback, playingReturn = playingReturn, isLeaving = !onPlayingPage)
    }
    DisposableEffect(morph.generation) {
        slot.element?.let(morph::onApplied)
        onDispose { }
    }
}

/** Plain holder, not state: the slot's element must not recompose anything. */
private class SlotElement {
    var element: HTMLElement? = null
}

/** From 760px, where the page sits in the shell beside its bar: M3's fade-through, fading in from 96 %. */
private fun fadeThrough(page: HTMLElement) {
    animateComposited(
        page,
        listOf(Keyframe.of(opacity = 0.0, transform = "scale($FADE_THROUGH_SCALE)"), Keyframe.of(1.0, "none")),
        MotionToken.MOVE,
    )
}

private fun Route.page(): String = segments.firstOrNull() ?: HOME_KEY

/** Below 760px in the shell's own window: the CSS contract's PHONE line (`00-base.css`). */
private fun isPhone(shell: Element): Boolean =
    shell.ownerDocument
        ?.defaultView
        ?.matchMedia(PHONE_QUERY)
        ?.matches == true

private fun currentProgress(shell: HTMLElement): Float =
    (shell.ownerDocument?.defaultView ?: window)
        .getComputedStyle(shell)
        .getPropertyValue(MORPH_PROGRESS)
        .trim()
        .toFloatOrNull() ?: 1f

private fun fontSizeOf(element: Element): Double =
    (element.ownerDocument?.defaultView ?: window)
        .getComputedStyle(element)
        .fontSize
        .removeSuffix("px")
        .toDoubleOrNull() ?: 1.0

private fun fontSizeOf(
    shell: HTMLElement,
    selector: String,
): Double = (shell.querySelector(selector) as? Element)?.let(::fontSizeOf) ?: 1.0

private fun HTMLElement.morphRect(): MorphRect {
    val box = getBoundingClientRect()
    return MorphRect(
        left = box.left.toFloat(),
        top = box.top.toFloat(),
        width = box.width.toFloat(),
        height = box.height.toFloat(),
    )
}

private fun progressFrame(value: Float): dynamic {
    val frame = js("{}")
    frame[MORPH_PROGRESS] = value
    return frame
}

private fun px(value: Number): String = "${value}px"

private fun ratio(value: Number): String = if (value.toDouble().isFinite()) value.toString() else "1"

private val FLIGHT_VARS = listOf("dx", "dy", "ox", "oy", "s", "sx", "sy")

/** The mini player's transport bar. */
private const val BAR_SELECTOR = ".tport"

private const val SHELL_MORPHING = "shell-morphing"

private const val SHELL_CROSSFADE = "mp-fade"

private const val PHONE_QUERY = "(max-width: 759px)"

/** The canvas `Reduce` board's crossfade, end to end. */
private const val CROSSFADE_MS = 150

/** Close enough to the end that there is nothing left to animate. */
private const val SETTLED = 0.0005f

private const val FADE_THROUGH_SCALE = 0.96
