package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.khronos.webgl.Int8Array
import org.w3c.dom.url.URL
import org.w3c.files.Blob

/**
 * One page's "Adjust photo" session: what the editor is showing, and what Done or Save does with the
 * frame. The editor opens at once and decodes behind it, so a slow photo shows a waiting dialog
 * rather than a button that seems to do nothing.
 *
 * Owns the object URL a decode of bytes makes and revokes it when the session ends, so a reader who
 * tries four pictures does not leak four blobs. A URL it was handed (an existing photo) is not its
 * to revoke.
 */
@Stable
class PhotoFraming internal constructor(
    private val scope: CoroutineScope,
) {
    /** The open session, or null when the editor is closed. */
    var session: Session? by mutableStateOf(null)
        private set

    private var decoding: Job? = null

    /** An open editor over [photo] (null while it decodes), starting from [initialFrame]. */
    class Session internal constructor(
        val photo: DecodedPhoto?,
        val initialFrame: ImageFrame?,
        val onFrame: (ImageFrame) -> Unit,
        internal val ownsUrl: Boolean,
    )

    /** Frame a photo the reader just picked, or one already staged, from its [bytes]. */
    fun openBytes(
        bytes: ByteArray,
        initialFrame: ImageFrame?,
        onFrame: (ImageFrame) -> Unit,
    ) = open(initialFrame, onFrame, ownsUrl = true) { decodePhoto(Blob(arrayOf(bytes.unsafeCast<Int8Array>()))) }

    /** Frame a photo the server already has, at [url]. */
    fun openUrl(
        url: String,
        initialFrame: ImageFrame?,
        onFrame: (ImageFrame) -> Unit,
    ) = open(initialFrame, onFrame, ownsUrl = false) { decodePhotoAt(url) }

    /** Close the editor, dropping anything still decoding. */
    fun close() {
        decoding?.cancel()
        decoding = null
        release(session)
        session = null
    }

    private fun open(
        initialFrame: ImageFrame?,
        onFrame: (ImageFrame) -> Unit,
        ownsUrl: Boolean,
        decode: suspend () -> DecodedPhoto,
    ) {
        close()
        session = Session(photo = null, initialFrame = initialFrame, onFrame = onFrame, ownsUrl = ownsUrl)
        decoding =
            scope.launch {
                val decoded = decode()
                session = Session(decoded, initialFrame, onFrame, ownsUrl)
            }
    }

    private fun release(ended: Session?) {
        val photo = ended?.photo
        if (ended?.ownsUrl == true && photo is DecodedPhoto.Ready) URL.revokeObjectURL(photo.url)
    }
}

/** A [PhotoFraming] for this composition, closed (and its URL revoked) when it leaves. */
@Composable
fun rememberPhotoFraming(): PhotoFraming {
    val scope = rememberCoroutineScope()
    val framing = remember(scope) { PhotoFraming(scope) }
    DisposableEffect(framing) { onDispose { framing.close() } }
    return framing
}

/**
 * The editor for [framing]'s open session, if there is one. Confirming hands the frame to the
 * session and closes; [onChooseAnother] runs after closing, for a photo that would not open.
 */
@Composable
fun PhotoFramingDialog(
    framing: PhotoFraming,
    confirm: FrameConfirm,
    onChooseAnother: () -> Unit,
) {
    val session = framing.session ?: return
    FrameEditorDialog(
        photo = session.photo,
        initialFrame = session.initialFrame,
        shape = FrameShape.Circle,
        confirm = confirm,
        onConfirm = { frame ->
            framing.close()
            session.onFrame(frame)
        },
        onCancel = framing::close,
        onChooseAnother = {
            framing.close()
            onChooseAnother()
        },
    )
}
