package com.calypsan.listenup.client.design.image

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.cookieScallopShape

/** What Adjust photo opens: picked bytes, an existing photo as a Coil model, or a pick that failed to decode. */
sealed interface FrameEditorSource {
    /** A freshly picked, already-normalised JPEG. */
    data class Bytes(
        val bytes: ByteArray,
    ) : FrameEditorSource {
        override fun equals(other: Any?): Boolean = other is Bytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** An existing photo, as any Coil model (a local path, or the contributor's resolved request). */
    data class Model(
        val model: Any,
    ) : FrameEditorSource

    /** The existing photo's request is still being resolved: the editor shows its loading state. */
    data object Resolving : FrameEditorSource

    /** The picker returned something that could not be decoded. */
    data object Undecodable : FrameEditorSource
}

/** The decoded photo the stage draws, or why there is none. Local UI state, never an AppError (spec §8). */
sealed interface FrameEditorImage {
    /** Still decoding. */
    data object Loading : FrameEditorImage

    /** Decoded: a [width]×[height] photo drawn by [painter]. */
    data class Ready(
        val width: Int,
        val height: Int,
        val painter: Painter,
    ) : FrameEditorImage

    /** It could not be decoded or loaded: the editor offers "Choose another" and Cancel. */
    data object CouldNotOpen : FrameEditorImage
}

/** The editor's confirm: "Done" stages inside a form, "Save" commits from a menu (Q2). */
enum class FrameConfirm { Done, Save }

/**
 * A live preview of the frame in a display shape the circle cut-out does not show (Android's scallop),
 * drawn [size] across under the editor.
 */
@Immutable
data class FramePreview(
    val shape: Shape,
    val size: Dp,
)

/**
 * The previews for a photo Android shows as a scallop (a contributor's detail hero, your own profile):
 * the 52 dp scallop and a 36 dp list circle, as on board `android-contributor`.
 */
@Composable
fun scallopFramePreviews(): List<FramePreview> {
    val scallop = cookieScallopShape()
    return remember(scallop) {
        listOf(
            FramePreview(scallop, SCALLOP_PREVIEW_SIZE),
            FramePreview(CircleShape, CIRCLE_PREVIEW_SIZE),
        )
    }
}

private val SCALLOP_PREVIEW_SIZE = 52.dp
private val CIRCLE_PREVIEW_SIZE = 36.dp
