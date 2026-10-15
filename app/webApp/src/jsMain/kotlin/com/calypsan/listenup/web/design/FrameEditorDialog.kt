package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.frameLayout
import com.calypsan.listenup.client.core.image.sliderPositionForZoom
import com.calypsan.listenup.client.core.image.zoomForSliderPosition
import com.calypsan.listenup.client.core.image.zoomLabel
import com.calypsan.listenup.client.presentation.imageframe.FrameEditor
import com.calypsan.listenup.client.presentation.imageframe.FrameFeedback
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the editor's confirm does, and so what it says (decided Q2). */
enum class FrameConfirm {
    /** Inside Edit profile or Contributor edit: the frame is staged, and the form's Save commits it. */
    Done,

    /** From the contributor page: the frame is saved at once. */
    Save,
}

/**
 * "Adjust photo": choose the part of a photo every avatar and contributor circle shows.
 *
 * A native `<dialog>` through [ModalDialog] (focus trapped, page inert, Escape closes), over the
 * shared [FrameEditor], so the clamp, the zoom range and the rising-edge feedback are the ones
 * Android and iOS run. The stage is a focusable group: arrow keys nudge (Up shows more of the top),
 * `+`/`-` zoom, `0` resets, a pointer drags, two pointers pinch, the wheel zooms about the cursor, and
 * the log slider zooms about the centre. Web has no haptics, so a polite live region says the zoom,
 * and "At edge" when a gesture first meets one.
 *
 * [photo] null is "still decoding": the dialog is up, the confirm waits. [DecodedPhoto.CouldNotOpen]
 * says so, disables the confirm and offers [onChooseAnother]. The cut-out is a circle on every
 * platform (Q13); [shape] is carried to the editor for the day a square is wanted.
 */
@Composable
fun FrameEditorDialog(
    photo: DecodedPhoto?,
    initialFrame: ImageFrame?,
    shape: FrameShape,
    confirm: FrameConfirm,
    onConfirm: (ImageFrame) -> Unit,
    onCancel: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    val confirmLabel = if (confirm == FrameConfirm.Done) "Done" else "Save"
    ModalDialog(open = true, title = "Adjust photo", onDismiss = onCancel, panelClass = PANEL) {
        when (photo) {
            is DecodedPhoto.Ready -> {
                key(photo) {
                    ReadyEditor(photo, initialFrame, shape, confirmLabel, onConfirm, onCancel)
                }
            }

            DecodedPhoto.CouldNotOpen -> {
                Head(confirmLabel, confirmEnabled = false, onConfirm = {}, onCancel = onCancel)
                CouldNotOpen(onChooseAnother)
                Foot(confirmLabel, confirmEnabled = false, onConfirm = {}, onCancel = onCancel, reset = null)
            }

            null -> {
                Head(confirmLabel, confirmEnabled = false, onConfirm = {}, onCancel = onCancel)
                Div(attrs = {
                    classes("fed-stage", "is-loading")
                    attr("aria-busy", "true")
                })
                Foot(confirmLabel, confirmEnabled = false, onConfirm = {}, onCancel = onCancel, reset = null)
            }
        }
    }
}

@Composable
private fun ReadyEditor(
    photo: DecodedPhoto.Ready,
    initialFrame: ImageFrame?,
    shape: FrameShape,
    confirmLabel: String,
    onConfirm: (ImageFrame) -> Unit,
    onCancel: () -> Unit,
) {
    val editor = remember { FrameEditor(photo.width, photo.height, initialFrame, shape) }
    val editing by editor.state.collectAsState()
    val frame = editing.frame
    var stageBox by remember { mutableStateOf(StageBox.Unmeasured) }
    var spoken by remember { mutableStateOf("") }
    val announcer = remember { Announcer { spoken = it } }
    val gestures = remember { FrameStageGestures(editor, onMeasured = { stageBox = it }, announce = announcer::say) }

    LaunchedEffect(editor) {
        editor.feedback.collect { feedback ->
            when (feedback) {
                FrameFeedback.EdgeReached -> announcer.say("At edge")
                FrameFeedback.ZoomLimitReached -> announcer.say("Zoom ${zoomLabel(editor.state.value.frame.zoom)}")
            }
        }
    }

    val confirmNow = { onConfirm(editor.state.value.frame) }
    Head(confirmLabel, confirmEnabled = true, onConfirm = confirmNow, onCancel = onCancel)

    Div(attrs = {
        classes("fed-stage")
        tabIndex(0)
        attr("role", "group")
        attr("aria-label", "Photo position")
        attr("aria-describedby", HINT_ID)
        ref { element ->
            gestures.attach(element)
            onDispose { gestures.detach() }
        }
    }) {
        if (stageBox.cut > 0.0) {
            StagePhoto(photo, frame, stageBox)
            Scrim(stageBox)
        }
        Span(attrs = {
            classes("fed-chip")
            attr("aria-hidden", "true")
        }) { Text("Drag to move · scroll or pinch to zoom") }
    }

    Div(attrs = {
        classes("fed-live", "sr-only")
        attr("aria-live", "polite")
    }) { Text(spoken) }

    ZoomRow(frame.zoom) { position -> gestures.zoomTo(zoomForSliderPosition(position)) }
    Hints()
    Foot(
        confirmLabel,
        confirmEnabled = true,
        onConfirm = confirmNow,
        onCancel = onCancel,
        reset = if (frame == ImageFrame.Centered) null else gestures::reset,
    )
}

/** The photo, placed in pixels: `frameLayout` inside the cut-out, then moved to where the cut-out sits. */
@Composable
private fun StagePhoto(
    photo: DecodedPhoto.Ready,
    frame: ImageFrame,
    box: StageBox,
) {
    val layout = frameLayout(photo.width.toDouble(), photo.height.toDouble(), box.cut, box.cut, frame)
    Img(src = photo.url, attrs = {
        classes("fed-photo")
        attr("alt", "")
        attr("draggable", "false")
        style {
            property("width", px(layout.width))
            property("height", px(layout.height))
            property("left", px(box.cutLeft + layout.offsetX))
            property("top", px(box.cutTop + layout.offsetY))
        }
    })
}

/**
 * The dark scrim with a circular hole, and the white ring around the hole. Fixed black and white in
 * both themes: a photo is judged against neutral black, and a theme-following ring would vanish.
 * Decorative; the stage carries the name.
 */
@Composable
private fun Scrim(box: StageBox) {
    val r = box.cut / 2
    val cx = box.cutLeft + r
    val cy = box.cutTop + r
    val circle = "M${num(
        cx - r,
    )} ${num(cy)}a${num(r)} ${num(r)} 0 1 0 ${num(2 * r)} 0a${num(r)} ${num(r)} 0 1 0 ${num(-2 * r)} 0Z"
    Svg({
        classes("fed-scrim")
        attr("aria-hidden", "true")
        attr("width", num(box.width))
        attr("height", num(box.height))
    }) {
        Path {
            attr("d", "M0 0H${num(box.width)}V${num(box.height)}H0Z $circle")
            attr("fill-rule", "evenodd")
            attr("fill", "rgba(0,0,0,0.62)")
        }
        Path {
            attr("d", circle)
            attr("fill", "none")
            attr("stroke", "#FFFFFF")
            attr("stroke-width", "2")
        }
    }
}

@Composable
private fun ZoomRow(
    zoom: Double,
    onSlide: (Double) -> Unit,
) {
    Div(attrs = { classes("fed-zoom") }) {
        Label(forId = ZOOM_ID) { Text("Zoom") }
        Input(type = InputType.Range) {
            id(ZOOM_ID)
            attr("min", "0")
            attr("max", SLIDER_STEPS.toString())
            attr("step", "1")
            // The implicit value is a raw step count; the zoom is what a listener recognises.
            attr("aria-valuetext", zoomLabel(zoom))
            value((sliderPositionForZoom(zoom) * SLIDER_STEPS).roundToInt().toString())
            onInput { event ->
                event.target.value
                    .toDoubleOrNull()
                    ?.let { onSlide(it / SLIDER_STEPS) }
            }
        }
        Span(attrs = { classes("fed-zoom-value") }) { Text(zoomLabel(zoom)) }
    }
}

/** The keys, for the keyboard; and on a phone, which has none, the touch gestures instead. */
@Composable
private fun Hints() {
    P(attrs = {
        classes("fed-hint", "fed-hint-keys")
        id(HINT_ID)
    }) {
        Span { Text("Arrow keys move") }
        listOf("←", "↑", "→", "↓").forEach { Kbd(it) }
        Span { Text("· zoom") }
        Kbd("+")
        Kbd("−")
        Span { Text("· reset") }
        Kbd("0")
    }
    P(attrs = { classes("fed-hint", "fed-hint-touch") }) { Text("Drag to move · pinch or use the slider to zoom") }
}

@Composable
private fun Kbd(key: String) {
    Span(attrs = { classes("fed-kbd") }) { Text(key) }
}

/**
 * The ✕, and on a phone the confirm beside it (board `web-phone`). Both copies of the confirm are in
 * the DOM and CSS shows one per width, so the desktop focus order stays Cancel → confirm in the foot.
 */
@Composable
private fun Head(
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Div(attrs = { classes("fed-head") }) {
        Button(kind = ButtonKind.Icon, onClick = { onCancel() }, label = "Cancel", attrs = { classes("fed-close") }) {
            Icon(WebIcon.X)
        }
        Button(
            kind = ButtonKind.Primary,
            onClick = { onConfirm() },
            enabled = confirmEnabled,
            attrs = { classes("fed-head-confirm") },
        ) { Text(confirmLabel) }
    }
}

/** Reset on the left (absent once there is nothing to reset), then Cancel and the confirm. */
@Composable
private fun Foot(
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    reset: (() -> Unit)?,
) {
    Div(attrs = { classes("fed-foot") }) {
        Button(kind = ButtonKind.Ghost, onClick = {
            reset?.invoke()
        }, enabled = reset != null, attrs = { classes("fed-reset") }) {
            Icon(WebIcon.Undo, size = SMALL_ICON)
            Text("Reset")
        }
        Div(attrs = { classes("fed-spacer") })
        Button(kind = ButtonKind.Secondary, onClick = { onCancel() }, attrs = { classes("fed-foot-cancel") }) {
            Text("Cancel")
        }
        Button(
            kind = ButtonKind.Primary,
            onClick = { onConfirm() },
            enabled = confirmEnabled,
            attrs = { classes("fed-foot-confirm") },
        ) { Text(confirmLabel) }
    }
}

/** Board `errors`, web column: the reason, what would work, and the way on. */
@Composable
private fun CouldNotOpen(onChooseAnother: () -> Unit) {
    Div(attrs = {
        classes("fed-error")
        attr("role", "alert")
    }) {
        H3(attrs = { classes("fed-error-t") }) { Text("Couldn't open this photo") }
        P(attrs = { classes("fed-error-p") }) { Text("Try a JPEG, PNG or WebP.") }
        Button(kind = ButtonKind.Primary, onClick = { onChooseAnother() }) { Text("Choose another") }
    }
}

/**
 * The stage's size and where its circular cut-out sits. The cut-out is the stage's short side less an
 * inset each side (board: 320 in a 400-tall desktop stage, 320 in a 390-wide phone stage).
 */
internal data class StageBox(
    val width: Double,
    val height: Double,
    val isPhone: Boolean,
) {
    private val inset: Double get() = if (isPhone) PHONE_INSET else DESKTOP_INSET

    /** The cut-out's diameter, which is also the editor's viewport. */
    val cut: Double get() = (min(width, height) - 2 * inset).coerceAtLeast(0.0)

    val cutLeft: Double get() = (width - cut) / 2

    val cutTop: Double get() = (height - cut) / 2

    companion object {
        val Unmeasured = StageBox(0.0, 0.0, isPhone = false)
    }
}

/**
 * Says one thing at a time in the live region, at most every half second: a slider dragged end to end
 * would otherwise queue thirty announcements. The last word always lands.
 */
private class Announcer(
    private val speak: (String) -> Unit,
) {
    private var lastSpokeAt = Double.NEGATIVE_INFINITY
    private var pending: Int? = null

    fun say(text: String) {
        pending?.let(window::clearTimeout)
        pending = null
        val now = window.performance.now()
        val wait = lastSpokeAt + ANNOUNCE_GAP_MS - now
        if (wait <= 0) {
            lastSpokeAt = now
            speak(text)
        } else {
            pending =
                window.setTimeout({
                    lastSpokeAt = window.performance.now()
                    pending = null
                    speak(text)
                }, wait.roundToInt())
        }
    }
}

/** CSS pixels, to two decimals. */
private fun px(value: Double): String = "${num(value)}px"

/** [value] to two decimals: enough for a pixel, and never an exponent a CSS or SVG parser would refuse. */
private fun num(value: Double): String = ((value * HUNDRED).roundToLong() / HUNDRED).toString()

private const val PANEL = "fed"

private const val HINT_ID = "fed-hint"

private const val ZOOM_ID = "fed-zoom"

/** The slider's travel in steps: fine enough that the zoom label moves smoothly at 4×. */
private const val SLIDER_STEPS = 1000

private const val SMALL_ICON = 16

private const val HUNDRED = 100.0

private const val ANNOUNCE_GAP_MS = 500.0

private const val PHONE_INSET = 35.0

private const val DESKTOP_INSET = 40.0
