package com.calypsan.listenup.web.design

import com.calypsan.listenup.client.core.image.NudgeDirection
import com.calypsan.listenup.client.core.image.ZOOM_STEP
import com.calypsan.listenup.client.core.image.wheelZoomFactor
import com.calypsan.listenup.client.core.image.zoomLabel
import com.calypsan.listenup.client.presentation.imageframe.FrameEditor
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.hypot

/**
 * The editor stage's input: pointer drag and pinch, the wheel, and the keys, each turned into one
 * [FrameEditor] step in the stage's own pixels.
 *
 * Every step measures the stage first. The stage is laid out only once its `<dialog>` is open, which
 * happens after this attaches, so a size read once at attach time would be zero — and a resize (a
 * phone rotating) would leave the editor's viewport stale. A ResizeObserver keeps the drawing in step
 * between gestures. A step that changes the zoom label says it, through [announce].
 */
internal class FrameStageGestures(
    private val editor: FrameEditor,
    private val onMeasured: (StageBox) -> Unit,
    private val announce: (String) -> Unit,
) {
    private var stage: HTMLElement? = null
    private var resizes: ResizeObserver? = null
    private var box = StageBox.Unmeasured

    /** Where each pointer on the stage is, in stage pixels: one is a drag, two a pinch. */
    private val pointers = LinkedHashMap<Int, Pair<Double, Double>>()

    private val onPointerDown: (Event) -> Unit = { event ->
        val pointer = event as PointerEvent
        pointers[pointer.pointerId] = local(pointer)
        capture(pointer.pointerId)
    }

    private val onPointerMove: (Event) -> Unit = { event -> move(event as PointerEvent) }

    private val onPointerEnd: (Event) -> Unit = { event -> pointers.remove((event as PointerEvent).pointerId) }

    private val onWheel: (Event) -> Unit = { event ->
        val wheel = event as WheelEvent
        wheel.preventDefault()
        val rect = stage?.getBoundingClientRect()
        val x = wheel.clientX - (rect?.left ?: 0.0)
        val y = wheel.clientY - (rect?.top ?: 0.0)
        step { editor.zoomBy(wheelZoomFactor(wheel.deltaY), x - box.cutLeft, y - box.cutTop) }
    }

    private val onKeyDown: (Event) -> Unit = { event -> key(event as KeyboardEvent) }

    fun attach(element: HTMLElement) {
        stage = element
        element.addEventListener("pointerdown", onPointerDown)
        element.addEventListener("pointermove", onPointerMove)
        listOf(
            "pointerup",
            "pointercancel",
            "lostpointercapture",
        ).forEach { element.addEventListener(it, onPointerEnd) }
        // Not passive: the wheel zooms the photo, and must not also scroll the page under the dialog.
        element.addEventListener("wheel", onWheel, js("({ passive: false })"))
        element.addEventListener("keydown", onKeyDown)
        resizes = ResizeObserver { _, _ -> measure() }.also { it.observe(element) }
        window.requestAnimationFrame { measure() }
    }

    fun detach() {
        val element = stage ?: return
        element.removeEventListener("pointerdown", onPointerDown)
        element.removeEventListener("pointermove", onPointerMove)
        listOf(
            "pointerup",
            "pointercancel",
            "lostpointercapture",
        ).forEach { element.removeEventListener(it, onPointerEnd) }
        element.removeEventListener("wheel", onWheel)
        element.removeEventListener("keydown", onKeyDown)
        resizes?.disconnect()
        resizes = null
        stage = null
    }

    /** The slider: zoom about the centre. */
    fun zoomTo(zoom: Double) = step { editor.zoomTo(zoom) }

    fun reset() = step { editor.reset() }

    private fun key(event: KeyboardEvent) {
        val zoom = editor.state.value.frame.zoom
        val action: (() -> Unit)? =
            when (event.key) {
                "ArrowUp" -> { -> editor.nudge(NudgeDirection.Up, large = event.shiftKey) }
                "ArrowDown" -> { -> editor.nudge(NudgeDirection.Down, large = event.shiftKey) }
                "ArrowLeft" -> { -> editor.nudge(NudgeDirection.Left, large = event.shiftKey) }
                "ArrowRight" -> { -> editor.nudge(NudgeDirection.Right, large = event.shiftKey) }
                "+", "=" -> { -> editor.zoomTo(zoom * ZOOM_STEP) }
                "-", "_", "−" -> { -> editor.zoomTo(zoom / ZOOM_STEP) }
                "0" -> { -> editor.reset() }
                else -> null
            }
        if (action != null) {
            event.preventDefault()
            step(action)
        }
    }

    private fun move(pointer: PointerEvent) {
        val previous = pointers[pointer.pointerId] ?: return
        val now = local(pointer)
        if (pointers.size == 1) {
            pointers[pointer.pointerId] = now
            step { editor.pan(now.first - previous.first, now.second - previous.second) }
            return
        }
        val (first, second) = pointers.entries.take(2).map { it.key }
        if (pointer.pointerId != first && pointer.pointerId != second) return
        val before = listOf(pointers.getValue(first), pointers.getValue(second))
        pointers[pointer.pointerId] = now
        val after = listOf(pointers.getValue(first), pointers.getValue(second))
        val spreadBefore = distance(before)
        val spreadAfter = distance(after)
        val (midX, midY) = midpoint(after)
        val (oldMidX, oldMidY) = midpoint(before)
        step {
            if (spreadBefore > 0.0) editor.zoomBy(spreadAfter / spreadBefore, midX - box.cutLeft, midY - box.cutTop)
            editor.pan(midX - oldMidX, midY - oldMidY)
        }
    }

    /** Measure, run [action], and say the zoom if its label moved. */
    private fun step(action: () -> Unit) {
        measure()
        val before = zoomLabel(editor.state.value.frame.zoom)
        action()
        val after = zoomLabel(editor.state.value.frame.zoom)
        if (after != before) announce("Zoom $after")
    }

    private fun measure() {
        val element = stage ?: return
        val measured =
            StageBox(
                width = element.clientWidth.toDouble(),
                height = element.clientHeight.toDouble(),
                isPhone = window.innerWidth < PHONE_BREAKPOINT,
            )
        box = measured
        if (measured.cut > 0.0) editor.setViewport(measured.cut)
        onMeasured(measured)
    }

    private fun local(pointer: PointerEvent): Pair<Double, Double> {
        val rect = stage?.getBoundingClientRect()
        return (pointer.clientX - (rect?.left ?: 0.0)) to (pointer.clientY - (rect?.top ?: 0.0))
    }

    /** Pointer capture keeps a drag alive past the stage's edge; a synthetic pointer may refuse it. */
    private fun capture(pointerId: Int) {
        try {
            stage?.asDynamic()?.setPointerCapture(pointerId)
        } catch (_: Throwable) {
            // Not capturable (a synthetic event): the drag still works while the pointer stays on the stage.
        }
    }
}

private fun distance(points: List<Pair<Double, Double>>): Double =
    hypot(points[1].first - points[0].first, points[1].second - points[0].second)

private fun midpoint(points: List<Pair<Double, Double>>): Pair<Double, Double> =
    (points[0].first + points[1].first) / 2 to (points[0].second + points[1].second) / 2

/** The phone layout's edge (`BreakpointsTest`: 639/640). */
private const val PHONE_BREAKPOINT = 640
