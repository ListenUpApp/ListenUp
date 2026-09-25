package com.calypsan.listenup.web.features.chaptereditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineChapter
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineLane
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.chapterDensity
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.events.WheelEvent

private const val MINIMAP_BUCKETS = 90

/** One zoom button press, or one wheel notch: a fifth narrower, or a quarter wider. */
private const val ZOOM_IN_STEP = 0.8f
private const val ZOOM_OUT_STEP = 1.25f

/** The faintest a bucket is drawn, so an empty stretch of the book still reads as part of it. */
private const val MIN_BUCKET_OPACITY = 0.12
private const val PERCENT = 100.0

/**
 * The editor's timeline on the web: the whole-book minimap over the draggable detail lane (spec
 * §7.3), driven by the shared [TimelineLane] the phones use.
 *
 * A pointer drag on a boundary previews while it moves and commits once on release; pulling away
 * vertically slows it, and Shift is the finest step (§7.8). A drag in open lane pans, the wheel
 * zooms around the pointer, and the minimap moves the window. The buttons zoom for anyone without
 * a wheel.
 *
 * ⛔ The DOM listeners are attached once, through `ref`, and read everything through
 * [rememberUpdatedState]: a listener capturing the lane it was created with would fold every
 * movement into the state from when the page first drew.
 */
@Composable
internal fun ChapterTimeline(
    state: ChapterEditorUiState.Editing,
    lane: TimelineLane,
    onLaneChange: (TimelineLane) -> Unit,
    playheadMs: Long?,
    ghosts: List<TimelineChapter>,
    onRetime: (String, Long) -> Unit,
) {
    val duration = state.bookDurationMs
    val previewed = lane.preview(state.chapters, duration)
    val markers =
        previewed.mapIndexed { index, chapter ->
            TimelineChapter(
                id = chapter.id,
                number = index + 1,
                startMs = chapter.startTime,
                locked = chapter.id in state.lockedChapterIds,
                selected = chapter.id == state.selectedChapterId,
            )
        }
    val live =
        LiveTimeline(
            lane = rememberUpdatedState(lane),
            markers = rememberUpdatedState(markers),
            chapters = rememberUpdatedState(state.chapters),
            duration = rememberUpdatedState(duration),
            onLaneChange = rememberUpdatedState(onLaneChange),
            onRetime = rememberUpdatedState(onRetime),
        )
    val window = lane.geometry

    Section(attrs = {
        classes("ctl")
        attr("aria-label", "Chapter timeline")
    }) {
        Div(attrs = { classes("ctl-head") }) {
            Span(attrs = { classes("ctl-h") }) { Text("Whole book") }
            Span(attrs = { classes("ctl-sub") }) { Text(ChapterTimeFormat.clock(duration)) }
        }
        MiniMap(state.chapters, duration, window.windowStartMs, window.windowEndMs, live)
        Div(attrs = { classes("ctl-head") }) {
            Span(attrs = { classes("ctl-h") }) { Text("Detail lane") }
            Span(attrs = { classes("ctl-sub") }) {
                Text(
                    "${ChapterTimeFormat.clock(window.windowStartMs)} – ${ChapterTimeFormat.clock(window.windowEndMs)}",
                )
            }
            Span(attrs = { classes("ctl-zooms") }) {
                ZoomButton(WebIcon.Minus, "Zoom out") { onLaneChange(lane.zoomedAroundCentre(ZOOM_OUT_STEP, duration)) }
                ZoomButton(WebIcon.Plus, "Zoom in") { onLaneChange(lane.zoomedAroundCentre(ZOOM_IN_STEP, duration)) }
            }
            // Its own line under the controls, so it never squeezes into a column at phone width.
            Span(attrs = { classes("ctl-hint") }) {
                Text("Scroll to zoom · drag a marker, pull away or hold Shift to fine-tune")
            }
        }
        DetailLane(state, lane, markers, playheadMs, ghosts, live)
    }
}

/** What the DOM listeners read: always the latest, never what was current when they attached. */
private class LiveTimeline(
    val lane: State<TimelineLane>,
    val markers: State<List<TimelineChapter>>,
    val chapters: State<List<Chapter>>,
    val duration: State<Long>,
    val onLaneChange: State<(TimelineLane) -> Unit>,
    val onRetime: State<(String, Long) -> Unit>,
)

@Composable
private fun MiniMap(
    chapters: List<Chapter>,
    duration: Long,
    windowStartMs: Long,
    windowEndMs: Long,
    live: LiveTimeline,
) {
    val density = chapterDensity(chapters.map { it.startTime }, duration, MINIMAP_BUCKETS)
    Div(attrs = {
        classes("ctl-map")
        attr("aria-label", "Whole book overview; drag to move the detail lane")
        ref { element ->
            var pressed: Int? = null

            fun centreAt(event: PointerEvent) {
                val rect = element.getBoundingClientRect()
                if (rect.width <= 0.0) return
                val fraction = ((event.clientX - rect.left) / rect.width).coerceIn(0.0, 1.0)
                val total = live.duration.value
                live.onLaneChange.value(live.lane.value.centredOn((fraction * total).toLong(), total))
            }
            val down: (Event) -> Unit = { event ->
                val pointer = event as PointerEvent
                pressed = pointer.pointerId
                capture(element, pointer.pointerId)
                centreAt(pointer)
            }
            val move: (Event) -> Unit = { event ->
                val pointer = event as PointerEvent
                if (pressed == pointer.pointerId) centreAt(pointer)
            }
            val up: (Event) -> Unit = { pressed = null }
            element.addEventListener("pointerdown", down)
            element.addEventListener("pointermove", move)
            element.addEventListener("pointerup", up)
            element.addEventListener("pointercancel", up)
            onDispose {
                element.removeEventListener("pointerdown", down)
                element.removeEventListener("pointermove", move)
                element.removeEventListener("pointerup", up)
                element.removeEventListener("pointercancel", up)
            }
        }
    }) {
        density.forEach { weight ->
            Div(attrs = {
                classes("ctl-bk")
                style { property("opacity", (MIN_BUCKET_OPACITY + (1 - MIN_BUCKET_OPACITY) * weight).toString()) }
            })
        }
        Div(attrs = {
            classes("ctl-view")
            style {
                property("left", pct(windowStartMs, 0L, duration))
                property("width", pct(windowEndMs - windowStartMs + 0L, 0L, duration))
            }
        })
    }
}

@Composable
private fun DetailLane(
    state: ChapterEditorUiState.Editing,
    lane: TimelineLane,
    markers: List<TimelineChapter>,
    playheadMs: Long?,
    ghosts: List<TimelineChapter>,
    live: LiveTimeline,
) {
    val start = lane.geometry.windowStartMs
    val end = lane.geometry.windowEndMs
    Div(attrs = {
        classes("ctl-lane")
        attr("aria-label", "Chapter timeline showing ${state.chapters.size} chapters")
        ref { element ->
            // The gesture keeps its own working lane between events: a movement that arrives
            // before recomposition would otherwise fold into a stale copy and lose travel.
            var working = live.lane.value
            var pointer: Int? = null
            var lastX = 0.0
            var startY = 0.0

            fun push(next: TimelineLane) {
                working = next
                live.onLaneChange.value(next)
            }
            val down: (Event) -> Unit = { event ->
                val press = event as PointerEvent
                if (press.button.toInt() == 0) {
                    val rect = element.getBoundingClientRect()
                    pointer = press.pointerId
                    lastX = press.clientX.toDouble()
                    startY = press.clientY.toDouble()
                    capture(element, press.pointerId)
                    val measured = live.lane.value.measured(rect.width.toFloat())
                    push(measured.grabbed((press.clientX - rect.left).toFloat(), live.markers.value))
                    press.preventDefault()
                }
            }
            val move: (Event) -> Unit = { event ->
                val motion = event as PointerEvent
                if (pointer == motion.pointerId) {
                    val dx = (motion.clientX - lastX).toFloat()
                    lastX = motion.clientX.toDouble()
                    push(
                        if (working.drag == null) {
                            // Open lane: dragging moves the window, not a boundary.
                            working.panned(dx, live.duration.value)
                        } else {
                            // CSS pixels stand in for dp: the fine-scrub steps are about distance
                            // pulled, and a CSS pixel is the web's device-independent unit.
                            working.dragged(dx, (motion.clientY - startY).toFloat(), shiftHeld = motion.shiftKey)
                        },
                    )
                }
            }
            val up: (Event) -> Unit = { event ->
                val release = event as PointerEvent
                if (pointer == release.pointerId) {
                    pointer = null
                    val active = working.drag
                    val landing = working.committedStartMs(live.chapters.value, live.duration.value)
                    // One drag, one edit: committed here, never per movement.
                    if (active != null && landing != null) live.onRetime.value(active.chapterId, landing)
                    push(working.released())
                }
            }
            val cancel: (Event) -> Unit = {
                pointer = null
                push(working.released())
            }
            val wheel: (Event) -> Unit = { event ->
                val notch = event as WheelEvent
                notch.preventDefault()
                val rect = element.getBoundingClientRect()
                val step = if (notch.deltaY > 0) ZOOM_OUT_STEP else ZOOM_IN_STEP
                val measured = live.lane.value.measured(rect.width.toFloat())
                push(measured.zoomed(step, (notch.clientX - rect.left).toFloat(), live.duration.value))
            }
            // ⛔ Not passive: the wheel zooms the lane, and must not also scroll the page under it.
            val notPassive: dynamic = js("({ passive: false })")
            element.addEventListener("pointerdown", down)
            element.addEventListener("pointermove", move)
            element.addEventListener("pointerup", up)
            element.addEventListener("pointercancel", cancel)
            element.addEventListener("wheel", wheel, notPassive)
            onDispose {
                element.removeEventListener("pointerdown", down)
                element.removeEventListener("pointermove", move)
                element.removeEventListener("pointerup", up)
                element.removeEventListener("pointercancel", cancel)
                element.removeEventListener("wheel", wheel)
            }
        }
    }) {
        state.fileBoundaries.forEach { file ->
            Div(attrs = {
                classes("ctl-file")
                attr("title", file.label)
                style { property("left", pct(file.startMs, start, end)) }
            })
        }
        ghosts.forEach { ghost ->
            Div(attrs = {
                classes("ctl-mk", "ctl-ghost")
                style { property("left", pct(ghost.startMs, start, end)) }
            })
        }
        markers.forEach { marker ->
            Div(attrs = {
                classes("ctl-mk")
                if (marker.selected) classes("on")
                if (marker.locked) classes("locked")
                attr("data-chapter", marker.id)
                style { property("left", pct(marker.startMs, start, end)) }
            }) { Span(attrs = { classes("ctl-mk-n") }) { Text(marker.number.toString()) } }
        }
        playheadMs?.let { at ->
            Div(attrs = {
                classes("ctl-ph")
                style { property("left", pct(at, start, end)) }
            })
        }
        // The step the pull landed in and the time the boundary would take (spec §7.2).
        lane.readout(state.chapters, state.bookDurationMs)?.let { readout ->
            Div(attrs = {
                classes("ctl-hud")
                attr("role", "status")
            }) { Text(readout) }
        }
    }
}

@Composable
private fun ZoomButton(
    icon: WebIcon,
    label: String,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("ctl-zoom")
        attr("type", "button")
        attr("aria-label", label)
        attr("title", label)
        onClick { onClick() }
    }) { Icon(icon, size = ZOOM_ICON) }
}

private const val ZOOM_ICON = 14

/** Where [ms] sits between [from] and [to], as a CSS percentage; outside the range lies off the lane. */
private fun pct(
    ms: Long,
    from: Long,
    to: Long,
): String {
    val span = (to - from).toDouble()
    val fraction = if (span <= 0.0) 0.0 else (ms - from) / span
    return "${fraction * PERCENT}%"
}

/** Pointer capture keeps a drag alive past the element's edge; a synthetic pointer may refuse it. */
private fun capture(
    element: HTMLElement,
    pointerId: Int,
) {
    try {
        element.asDynamic().setPointerCapture(pointerId)
    } catch (_: Throwable) {
        // Not capturable (a synthetic event, or a pointer already released) — the drag still works
        // while the pointer stays over the lane.
    }
}
