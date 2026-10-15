package com.calypsan.listenup.client.design.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.crossfade
import coil3.request.ImageRequest
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.canonical
import com.calypsan.listenup.client.core.image.sliderPositionForZoom
import com.calypsan.listenup.client.core.image.zoomForSliderPosition
import com.calypsan.listenup.client.core.image.zoomLabel
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.imageframe.FrameEditor
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_done
import listenup.composeapp.generated.resources.common_save
import listenup.composeapp.generated.resources.edit_frame_choose_another
import listenup.composeapp.generated.resources.edit_frame_could_not_open
import listenup.composeapp.generated.resources.edit_frame_could_not_open_body
import listenup.composeapp.generated.resources.edit_frame_preview_label
import listenup.composeapp.generated.resources.edit_frame_reset
import listenup.composeapp.generated.resources.edit_frame_title
import listenup.composeapp.generated.resources.edit_frame_zoom
import listenup.composeapp.generated.resources.edit_frame_zoom_value
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** The tablet dialog's width and stage, from board `android-tablet`: a 560 dp dialog around a 512×384 dp stage. */
private val DIALOG_WIDTH = 560.dp
private val DIALOG_STAGE_HEIGHT = 384.dp
private const val CENTRE_SLACK = 1e-6

/**
 * Adjust photo: the photo fills the stage behind a dimmed scrim, with a circle cut-out showing what
 * every avatar and contributor photo will show (Q13: the scallop is a display shape only). Drag
 * moves, pinch or the slider zooms, Reset returns to the centre crop.
 *
 * A full-screen dialog on compact widths and a basic dialog from 600 dp (board `android-tablet`).
 * [confirm] is Done inside a form, which stages, and Save from a menu, which commits (Q2). [onConfirm]
 * receives the canonical frame (spec §6.2), so what is saved is what was shown. [previews] draw the
 * live frame in display shapes the cut-out does not show, captioned [previewLabel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameEditorDialog(
    source: FrameEditorSource,
    initialFrame: ImageFrame?,
    shape: FrameShape,
    confirm: FrameConfirm,
    onConfirm: (ImageFrame) -> Unit,
    onCancel: () -> Unit,
    onChooseAnother: () -> Unit,
    previews: List<FramePreview> = emptyList(),
    previewLabel: StringResource = Res.string.edit_frame_preview_label,
) {
    val image = rememberFrameEditorImage(source)
    val content: @Composable () -> Unit = {
        FrameEditorContent(
            image = image,
            initialFrame = initialFrame,
            shape = shape,
            confirm = confirm,
            previews = previews,
            onConfirm = onConfirm,
            onCancel = onCancel,
            onChooseAnother = onChooseAnother,
            previewLabel = previewLabel,
        )
    }
    if (isWideEditor()) {
        BasicAlertDialog(onDismissRequest = onCancel, content = content)
    } else {
        Dialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(usePlatformDefaultWidth = false),
            content = content,
        )
    }
}

/** From 600 dp the editor is a basic dialog; below it, a full-screen dialog (M3 dialogs guidance). */
@Composable
private fun isWideEditor(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )

/**
 * Decodes what the editor opens. A pick that failed to decode, or an existing photo that will not load
 * (offline and uncached, say), is [FrameEditorImage.CouldNotOpen] (spec §7.6). The decode is capped at
 * the framed-decode size, which is what the pickers already normalise uploads to.
 */
@Composable
internal fun rememberFrameEditorImage(source: FrameEditorSource): FrameEditorImage {
    val model: Any =
        when (source) {
            is FrameEditorSource.Bytes -> source.bytes
            is FrameEditorSource.Model -> source.model
            FrameEditorSource.Resolving -> return FrameEditorImage.Loading
            FrameEditorSource.Undecodable -> return FrameEditorImage.CouldNotOpen
        }
    val context = LocalPlatformContext.current
    val request =
        remember(model) {
            val builder = (model as? ImageRequest)?.newBuilder() ?: ImageRequest.Builder(context).data(model)
            builder.size(MAX_FRAMED_DECODE_PX, MAX_FRAMED_DECODE_PX).crossfade(false).build()
        }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsStateWithLifecycle()
    return when (val current = state) {
        is AsyncImagePainter.State.Success -> {
            FrameEditorImage.Ready(current.result.image.width, current.result.image.height, painter)
        }

        is AsyncImagePainter.State.Error -> {
            FrameEditorImage.CouldNotOpen
        }

        AsyncImagePainter.State.Empty, is AsyncImagePainter.State.Loading -> {
            FrameEditorImage.Loading
        }
    }
}

/**
 * The editor without its window, so tests drive it directly. [onFrameChanged] reports every settled
 * frame; only the accessibility test listens.
 */
@Composable
internal fun FrameEditorContent(
    image: FrameEditorImage,
    initialFrame: ImageFrame?,
    shape: FrameShape,
    confirm: FrameConfirm,
    previews: List<FramePreview>,
    onConfirm: (ImageFrame) -> Unit,
    onCancel: () -> Unit,
    onChooseAnother: () -> Unit,
    onFrameChanged: (ImageFrame) -> Unit = {},
    previewLabel: StringResource = Res.string.edit_frame_preview_label,
) {
    val ready = image as? FrameEditorImage.Ready
    val editor =
        remember(ready?.width, ready?.height) {
            ready?.let { decoded ->
                FrameEditor(
                    imageWidth = decoded.width,
                    imageHeight = decoded.height,
                    initialFrame = initialFrame,
                    shape = shape,
                )
            }
        }
    val frame = editor?.run { state.collectAsStateWithLifecycle().value.frame }
    val latestOnFrameChanged by rememberUpdatedState(onFrameChanged)
    LaunchedEffect(frame) { frame?.let { latestOnFrameChanged(it) } }

    val haptics = LocalHaptics.current
    val confirmFrame: (() -> Unit)? =
        if (ready != null && frame != null) {
            {
                haptics.commit()
                onConfirm(frame.canonical(ready.width.toDouble(), ready.height.toDouble()))
            }
        } else {
            null
        }
    val wide = isWideEditor()
    val stage: @Composable (Modifier) -> Unit = { stageModifier ->
        StageArea(
            image = image,
            editor = editor,
            frame = frame,
            shape = shape,
            isDialog = wide,
            onChooseAnother = onChooseAnother,
            modifier = stageModifier,
        )
    }
    val controls: @Composable () -> Unit = {
        if (editor != null && ready != null && frame != null) {
            FrameControls(
                editor = editor,
                image = ready,
                frame = frame,
                previews = previews,
                previewLabel = previewLabel,
                isDialog = wide,
            )
        }
    }
    if (wide) {
        DialogEditor(
            confirm = confirm,
            onConfirm = confirmFrame,
            onCancel = onCancel,
            editor = editor,
            frame = frame,
            stage = stage,
            controls = controls,
        )
    } else {
        FullScreenEditor(
            confirm = confirm,
            onConfirm = confirmFrame,
            onCancel = onCancel,
            stage = stage,
            controls = controls,
        )
    }
}

/** Compact: a full-screen dialog with a close icon, the title, and the filled confirm (Decision 8). */
@Composable
private fun FullScreenEditor(
    confirm: FrameConfirm,
    onConfirm: (() -> Unit)?,
    onCancel: () -> Unit,
    stage: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.edit_frame_title),
                onBack = onCancel,
                navigationIcon = Icons.Default.Close,
                navigationContentDescription = stringResource(Res.string.common_cancel),
                actions = {
                    ConfirmButton(confirm, onConfirm, modifier = Modifier.padding(end = 8.dp))
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            stage(Modifier.weight(1f).fillMaxWidth())
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                controls()
            }
        }
    }
}

/** Expanded: a basic dialog with the stage, the slider, and Reset · Cancel · confirm (board `android-tablet`). */
@Composable
private fun DialogEditor(
    confirm: FrameConfirm,
    onConfirm: (() -> Unit)?,
    onCancel: () -> Unit,
    editor: FrameEditor?,
    frame: ImageFrame?,
    stage: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(DIALOG_WIDTH),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(Res.string.edit_frame_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            stage(Modifier.fillMaxWidth().height(DIALOG_STAGE_HEIGHT).clip(RoundedCornerShape(16.dp)))
            controls()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editor != null && frame != null) {
                    TextButton(onClick = editor::reset, enabled = !frame.isCentreCrop()) {
                        Text(stringResource(Res.string.edit_frame_reset))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                ConfirmButton(confirm, onConfirm)
            }
        }
    }
}

/** The filled confirm: "Done" in a form, "Save" from a menu; disabled until there is a photo to frame. */
@Composable
private fun ConfirmButton(
    confirm: FrameConfirm,
    onConfirm: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Button(onClick = { onConfirm?.invoke() }, enabled = onConfirm != null, modifier = modifier) {
        Text(
            stringResource(
                when (confirm) {
                    FrameConfirm.Done -> Res.string.common_done
                    FrameConfirm.Save -> Res.string.common_save
                },
            ),
        )
    }
}

/** The stage when there is a photo; otherwise the loading indicator or the could-not-open message, on the same black. */
@Composable
private fun StageArea(
    image: FrameEditorImage,
    editor: FrameEditor?,
    frame: ImageFrame?,
    shape: FrameShape,
    isDialog: Boolean,
    onChooseAnother: () -> Unit,
    modifier: Modifier,
) {
    if (image is FrameEditorImage.Ready && editor != null && frame != null) {
        FrameStage(
            editor = editor,
            image = image,
            frame = frame,
            shape = shape,
            isDialog = isDialog,
            modifier = modifier,
        )
        return
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.scrim), contentAlignment = Alignment.Center) {
        if (image == FrameEditorImage.CouldNotOpen) CouldNotOpen(onChooseAnother) else ListenUpLoadingIndicator()
    }
}

/** Board `errors`, Android column: a broken image, why, and the way out. Announced politely to TalkBack. */
@Composable
private fun CouldNotOpen(onChooseAnother: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp).semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Color.White,
        )
        Text(
            text = stringResource(Res.string.edit_frame_could_not_open),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.edit_frame_could_not_open_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Button(onClick = onChooseAnother) { Text(stringResource(Res.string.edit_frame_choose_another)) }
    }
}

/** The zoom slider, then (phone only) the previews and Reset on one row. */
@Composable
private fun FrameControls(
    editor: FrameEditor,
    image: FrameEditorImage.Ready,
    frame: ImageFrame,
    previews: List<FramePreview>,
    previewLabel: StringResource,
    isDialog: Boolean,
) {
    ZoomRow(editor, frame)
    if (isDialog) {
        if (previews.isNotEmpty()) {
            FramePreviews(
                image = image,
                frame = frame,
                previews = previews,
                label = previewLabel,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (previews.isEmpty()) Arrangement.Center else Arrangement.SpaceBetween,
    ) {
        if (previews.isNotEmpty()) {
            FramePreviews(
                image = image,
                frame = frame,
                previews = previews,
                label = previewLabel,
            )
        }
        OutlinedButton(onClick = editor::reset, enabled = !frame.isCentreCrop()) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.edit_frame_reset))
        }
    }
}

/**
 * The log zoom slider between its two magnifiers (HIG and M3 both: icons that illustrate the ends).
 * TalkBack hears "Zoom 2.2×", not the slider's default percentage.
 */
@Composable
private fun ZoomRow(
    editor: FrameEditor,
    frame: ImageFrame,
) {
    val zoomName = stringResource(Res.string.edit_frame_zoom)
    val zoomValue = stringResource(Res.string.edit_frame_zoom_value, zoomLabel(frame.zoom))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.ZoomOut, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = sliderPositionForZoom(frame.zoom).toFloat(),
            onValueChange = { editor.zoomTo(zoomForSliderPosition(it.toDouble())) },
            valueRange = 0f..1f,
            modifier =
                Modifier.weight(1f).semantics {
                    contentDescription = zoomName
                    stateDescription = zoomValue
                },
        )
        Icon(Icons.Default.ZoomIn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The same frame drawn live in the display shapes the circle cut-out does not show. */
@Composable
private fun FramePreviews(
    image: FrameEditorImage.Ready,
    frame: ImageFrame,
    previews: List<FramePreview>,
    label: StringResource,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        previews.forEach { preview ->
            Image(
                painter = image.painter,
                contentDescription = null,
                contentScale = FramedCrop(frame),
                alignment = FramedAlignment(frame),
                modifier = Modifier.size(preview.size).clip(preview.shape),
            )
        }
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The centre crop, give or take the rounding a canonical frame picks up. */
private fun ImageFrame.isCentreCrop(): Boolean =
    abs(focusX - ImageFrame.Centered.focusX) < CENTRE_SLACK &&
        abs(focusY - ImageFrame.Centered.focusY) < CENTRE_SLACK &&
        abs(zoom - ImageFrame.Centered.zoom) < CENTRE_SLACK
