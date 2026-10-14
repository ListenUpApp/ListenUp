package com.calypsan.listenup.client.presentation.imageframe

import com.calypsan.listenup.api.dto.image.ImageFrame

/**
 * The cut-out the frame editor shows. Every display shape is 1:1, so one frame serves them all. Decided (Q13): the
 * editor shows [Circle] for avatars and contributors, even where the photo is displayed as a scallop; [Square] is the
 * hook for Story World images.
 */
enum class FrameShape { Circle, Square }

/** The photo-framing editor's UI state. */
sealed interface FrameEditorState {
    /** The photo is still being decoded. */
    data object Loading : FrameEditorState

    /** The photo could not be decoded or loaded: the editor offers "Choose another" and Cancel. */
    data object CouldNotOpen : FrameEditorState

    /** Editing an [imageWidth]×[imageHeight] photo cut out as [shape], currently at [frame] (always canonical). */
    data class Ready(
        val imageWidth: Int,
        val imageHeight: Int,
        val frame: ImageFrame,
        val shape: FrameShape,
    ) : FrameEditorState
}
