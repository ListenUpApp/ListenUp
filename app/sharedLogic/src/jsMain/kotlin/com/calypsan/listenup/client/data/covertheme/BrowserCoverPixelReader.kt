package com.calypsan.listenup.client.data.covertheme

import com.calypsan.listenup.client.data.repository.CoverThemeRepositoryImpl
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.core.BookId
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageBitmapOptions
import org.w3c.dom.MEDIUM
import org.w3c.dom.ResizeQuality
import org.w3c.files.Blob
import kotlin.coroutines.cancellation.CancellationException

/**
 * The browser's cover colour source (Book Detail spec §4.4): the shared rule over a reader that fetches [coverUrl].
 * The fetch is same-origin, so the session cookie authenticates it. Public so `:app:webApp` binds it with its own
 * cover URL grammar and its specs can drive it; jsMain never reaches the Swift Export surface.
 */
fun browserCoverThemeRepository(coverUrl: (BookId, String?) -> String): CoverThemeRepository =
    CoverThemeRepositoryImpl(reader = BrowserCoverPixelReader(coverUrl))

/**
 * Fetches the cover, lets `createImageBitmap` decode and downsample it (off the main thread, in the browser), and reads
 * the pixels back from a detached canvas. A failed fetch (401, offline, a non-image) is "no cover", never an error.
 */
internal class BrowserCoverPixelReader(
    private val coverUrl: (BookId, String?) -> String,
) : CoverPixelReader {
    override suspend fun read(
        bookId: BookId,
        coverHash: String?,
        maxEdge: Int,
    ): CoverPixels? =
        try {
            val response = window.fetch(coverUrl(bookId, coverHash)).await()
            if (response.ok) pixelsOf(response.blob().await(), maxEdge) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A 401, offline, or a body the browser cannot decode: no cover, so the hero stays neutral.
            null
        }

    private suspend fun pixelsOf(
        blob: Blob,
        edge: Int,
    ): CoverPixels {
        val bitmap =
            window
                .createImageBitmap(
                    blob,
                    ImageBitmapOptions(resizeWidth = edge, resizeHeight = edge, resizeQuality = ResizeQuality.MEDIUM),
                ).await()
        val canvas =
            (document.createElement("canvas") as HTMLCanvasElement).apply {
                width = edge
                height = edge
            }
        val context = canvas.getContext("2d") as CanvasRenderingContext2D
        context.drawImage(bitmap, 0.0, 0.0)
        bitmap.close()
        val rgba = context.getImageData(0.0, 0.0, edge.toDouble(), edge.toDouble()).data
        return CoverPixels(
            edge,
            edge,
            IntArray(edge * edge) { i ->
                fun channel(offset: Int) = rgba[i * BYTES_PER_PIXEL + offset].toInt() and CHANNEL_MAX
                (channel(ALPHA_OFFSET) shl ALPHA_SHIFT) or (channel(RED_OFFSET) shl RED_SHIFT) or
                    (channel(GREEN_OFFSET) shl GREEN_SHIFT) or channel(BLUE_OFFSET)
            },
        )
    }
}

private const val BYTES_PER_PIXEL = 4
private const val CHANNEL_MAX = 0xFF
private const val RED_OFFSET = 0
private const val GREEN_OFFSET = 1
private const val BLUE_OFFSET = 2
private const val ALPHA_OFFSET = 3
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
