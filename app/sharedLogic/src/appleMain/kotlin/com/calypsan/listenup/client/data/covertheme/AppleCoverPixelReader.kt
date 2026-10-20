package com.calypsan.listenup.client.data.covertheme

import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

/**
 * iOS's [CoverPixelReader]: the cover file sync downloaded (`ImageStorage`), decoded as an ImageIO thumbnail so the
 * full-resolution bitmap is never materialised — the same approach as the app's Swift `ImageDownsampler`.
 */
internal class AppleCoverPixelReader(
    private val imageStorage: ImageStorage,
) : CoverPixelReader {
    override suspend fun read(
        bookId: BookId,
        coverHash: String?,
        maxEdge: Int,
    ): CoverPixels? {
        if (!imageStorage.exists(bookId)) return null
        return decodeCoverThumbnail(imageStorage.getCoverPath(bookId), maxEdge)
    }
}

/** [path] decoded to at most [maxEdge] pixels a side, as unpremultiplied sRGB ARGB; `null` if unreadable. */
@OptIn(ExperimentalForeignApi::class)
internal fun decodeCoverThumbnail(
    path: String,
    maxEdge: Int,
): CoverPixels? {
    @Suppress("UNCHECKED_CAST")
    val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef? ?: return null
    val source = CGImageSourceCreateWithURL(url, null)
    CFRelease(url)
    source ?: return null
    val options = thumbnailOptions(maxEdge)
    val image = CGImageSourceCreateThumbnailAtIndex(source, 0uL, options)
    CFRelease(options)
    CFRelease(source)
    image ?: return null
    return try {
        pixelsOf(image)
    } finally {
        CGImageRelease(image)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun thumbnailOptions(maxEdge: Int): CFDictionaryRef? =
    memScoped {
        val options =
            CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        val edge = alloc<IntVar>().apply { value = maxEdge }
        val number = CFNumberCreate(null, kCFNumberIntType, edge.ptr)
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
        CFRelease(number)
        options
    }

@OptIn(ExperimentalForeignApi::class)
private fun pixelsOf(image: CGImageRef): CoverPixels? {
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    if (width <= 0 || height <= 0) return null
    val rgba = ByteArray(width * height * BYTES_PER_PIXEL)
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return null
    val drawn =
        rgba.usePinned { pinned ->
            val context =
                CGBitmapContextCreate(
                    pinned.addressOf(0),
                    width.toULong(),
                    height.toULong(),
                    BITS_PER_CHANNEL,
                    (width * BYTES_PER_PIXEL).toULong(),
                    space,
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                ) ?: return@usePinned false
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
            CGContextRelease(context)
            true
        }
    CGColorSpaceRelease(space)
    if (!drawn) return null
    return CoverPixels(width, height, IntArray(width * height) { unpremultiplied(rgba, it * BYTES_PER_PIXEL) })
}

/** One RGBA pixel, premultiplied by its alpha in the context, as straight ARGB. */
private fun unpremultiplied(
    rgba: ByteArray,
    at: Int,
): Int {
    val alpha = rgba[at + ALPHA_OFFSET].toInt() and CHANNEL_MAX
    if (alpha == 0) return 0

    fun channel(offset: Int) =
        ((rgba[at + offset].toInt() and CHANNEL_MAX) * CHANNEL_MAX / alpha).coerceAtMost(CHANNEL_MAX)
    return (alpha shl ALPHA_SHIFT) or (channel(RED_OFFSET) shl RED_SHIFT) or (channel(GREEN_OFFSET) shl GREEN_SHIFT) or
        channel(BLUE_OFFSET)
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
private const val BITS_PER_CHANNEL = 8uL
