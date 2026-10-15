package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.awaitFrame
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.asList
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.coroutines.resume

/** A PNG of the given size, drawn in the page, so a spec needs no fixture file and no server. */
internal suspend fun pngBlob(
    width: Int,
    height: Int,
): Blob =
    suspendCancellableCoroutine { continuation ->
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = width
        canvas.height = height
        val context = canvas.getContext("2d") as CanvasRenderingContext2D
        context.fillStyle = "#888888"
        context.fillRect(0.0, 0.0, width.toDouble(), height.toDouble())
        canvas.toBlob({ blob -> continuation.resume(blob!!) }, "image/png")
    }

/** An object URL for a [pngBlob]; nothing is revoked, the page is torn down after the run. */
internal suspend fun portraitPngUrl(
    width: Int = 300,
    height: Int = 400,
): String = URL.createObjectURL(pngBlob(width, height))

/** Waits up to 50 frames for every `img` under [host] to finish loading or failing. */
internal suspend fun awaitImagesSettled(host: HTMLElement) {
    repeat(SETTLE_FRAMES) {
        val images = host.querySelectorAll("img").asList().map { it as HTMLImageElement }
        if (images.all { it.complete }) return
        awaitFrame()
    }
}

/** As [awaitImagesSettled], then one more frame so a `load` or `error` handler's recomposition lands. */
internal suspend fun awaitImagesApplied(host: HTMLElement) {
    awaitImagesSettled(host)
    awaitFrame()
    awaitFrame()
}

/** A CSS percentage such as `"-60.2941%"`, read back as a number. */
internal fun percentOf(css: String): Double = css.removeSuffix("%").toDouble()

private const val SETTLE_FRAMES = 50
