package com.calypsan.listenup.client.features.documentviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Wraps [android.graphics.pdf.PdfRenderer] with a safe, [Closeable] lifecycle.
 *
 * PdfRenderer is **not thread-safe** and only one page may be open at a time.
 * Callers are responsible for serialising [renderPage] calls (e.g. via a [kotlinx.coroutines.sync.Mutex]).
 *
 * @param path Absolute filesystem path to a PDF file.
 * @throws IllegalArgumentException if the file does not exist or cannot be opened.
 */
internal class PdfRendererWrapper(
    path: String,
) : Closeable {
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer =
        try {
            PdfRenderer(pfd)
        } catch (e: Throwable) {
            pfd.close()
            throw e
        }

    /** Number of pages in the document. */
    val pageCount: Int get() = renderer.pageCount

    /**
     * Renders the page at [index] into a new [Bitmap] scaled to [targetWidthPx] wide.
     *
     * The bitmap uses [Bitmap.Config.ARGB_8888] and is filled with white before rendering
     * so PDF transparency resolves correctly on-screen.
     *
     * @param index Zero-based page index (0 until [pageCount]).
     * @param targetWidthPx Width in pixels the output bitmap should occupy.
     * @return A freshly-allocated [Bitmap] the caller owns; recycle when done.
     */
    internal fun renderPage(
        index: Int,
        targetWidthPx: Int,
    ): Bitmap {
        val page = renderer.openPage(index)
        try {
            if (page.width <= 0 ||
                targetWidthPx <= 0
            ) {
                error("Degenerate page dimensions: width=${page.width}, targetWidthPx=$targetWidthPx")
            }
            val targetHeight = (page.height.toLong() * targetWidthPx / page.width).toInt()
            val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeight, Bitmap.Config.ARGB_8888)
            // Fill white so semi-transparent PDF content renders correctly.
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }
}

/** Returns the bare filename (with extension) from an absolute path. */
internal fun basenameOf(path: String): String = File(path).name
