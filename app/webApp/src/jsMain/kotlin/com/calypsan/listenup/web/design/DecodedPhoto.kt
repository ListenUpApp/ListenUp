package com.calypsan.listenup.web.design

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.js.Promise

/** A photo ready to frame — a URL the editor can draw and its upright size — or why there is none. */
sealed interface DecodedPhoto {
    /** [url] drawn at [width]×[height], the size every renderer of it uses. */
    data class Ready(
        val url: String,
        val width: Int,
        val height: Int,
    ) : DecodedPhoto

    /** Nothing the browser can draw: the editor says so and offers another (spec §7.4). */
    data object CouldNotOpen : DecodedPhoto
}

/**
 * Decodes a picked [blob] the way the browser will draw it. `createImageBitmap` applies EXIF
 * orientation by default, so the size is the upright one an `<img>` shows and the frame is measured
 * against. A rejection is [DecodedPhoto.CouldNotOpen], never thrown. The caller owns, and revokes, the
 * object URL in the result.
 */
suspend fun decodePhoto(blob: Blob): DecodedPhoto =
    try {
        val bitmap = createImageBitmap(blob).await()
        val width = bitmap.width
        val height = bitmap.height
        bitmap.close()
        if (width > 0 &&
            height > 0
        ) {
            DecodedPhoto.Ready(URL.createObjectURL(blob), width, height)
        } else {
            DecodedPhoto.CouldNotOpen
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        DecodedPhoto.CouldNotOpen
    }

/**
 * An existing photo by [url], authenticated by the cookie the browser holds: loaded once to learn
 * its size. A failed load — a 404, a photo the browser cannot read — is [DecodedPhoto.CouldNotOpen].
 */
suspend fun decodePhotoAt(url: String): DecodedPhoto =
    suspendCancellableCoroutine { continuation ->
        val img = document.createElement("img") as HTMLImageElement
        img.onload = {
            val ready = img.naturalWidth > 0 && img.naturalHeight > 0
            continuation.resume(
                if (ready) DecodedPhoto.Ready(url, img.naturalWidth, img.naturalHeight) else DecodedPhoto.CouldNotOpen,
            )
        }
        img.onerror = { _, _, _, _, _ ->
            continuation.resume(DecodedPhoto.CouldNotOpen)
            null
        }
        continuation.invokeOnCancellation { img.src = "" }
        img.src = url
    }

/** The decoded bitmap, as much of it as the decode reads. Typed, so nothing here dispatches on `dynamic`. */
private external interface DecodedBitmap {
    val width: Int
    val height: Int

    fun close()
}

private fun createImageBitmap(blob: Blob): Promise<DecodedBitmap> =
    window.asDynamic().createImageBitmap(blob).unsafeCast<Promise<DecodedBitmap>>()
