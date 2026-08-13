package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.logging.loggerFor
import io.ktor.http.ContentType
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val log = loggerFor<CoverContentResolver>()

/** A cover's full-size bytes paired with the content type they should be served under. */
class CoverContent(
    val bytes: ByteArray,
    val contentType: ContentType,
)

/**
 * Turns a resolved [CoverInfo] into the cover's actual bytes, whichever kind of cover it is.
 *
 * The single answer to "what *is* this book's cover", so the two things that need those bytes —
 * [CoverResponder] serving them and [CoverDerivativeMaintenance] deriving from them — cannot
 * disagree. `null` throughout: a cover whose file vanished or whose audio will not parse is a 404
 * to one caller and a skip to the other, never an error to either.
 *
 * The constructor is `internal` — it depends on the `internal` [EmbeddedMetadataParser].
 *
 * @param cache the LRU cache for extracted embedded artwork; an audio file is parsed once.
 * @param parser the embedded-metadata parser used to extract artwork.
 */
class CoverContentResolver internal constructor(
    private val cache: EmbeddedCoverCache,
    private val parser: EmbeddedMetadataParser,
) {
    /** [info]'s bytes and content type, or `null` when they cannot be produced. */
    suspend fun content(
        id: BookId,
        info: CoverInfo,
    ): CoverContent? =
        when (info) {
            is CoverInfo.Filesystem -> fileContent(info.path)
            is CoverInfo.Managed -> fileContent(info.path)
            is CoverInfo.Embedded -> embeddedContent(id, info.audioFilePath)
        }

    private suspend fun fileContent(path: Path): CoverContent? {
        val bytes =
            withContext(fileIoDispatcher) {
                if (SystemFileSystem.metadataOrNull(path)?.isRegularFile != true) null else path.readBytes()
            }
        return bytes?.let { CoverContent(it, contentTypeForExtension(path)) }
    }

    private suspend fun embeddedContent(
        id: BookId,
        audioFilePath: Path,
    ): CoverContent? {
        val artwork =
            cache.getOrCompute(id) {
                when (val result = parser.parse(audioFilePath)) {
                    is AppResult.Success -> {
                        result.data.artwork
                    }

                    is AppResult.Failure -> {
                        log.warn { "embedded cover extraction failed for $id: ${result.error.code}" }
                        null
                    }
                }
            } ?: return null
        return CoverContent(
            artwork.bytes,
            runCatching { ContentType.parse(artwork.mime) }.getOrDefault(ContentType.Image.JPEG),
        )
    }

    /**
     * Maps a cover image file's extension to its [ContentType]. Unknown extensions fall back to
     * `image/jpeg` — the scanner only ingests `png`/`jpg`/`jpeg`/`webp`, so this is defensive.
     */
    private fun contentTypeForExtension(path: Path): ContentType =
        when (
            path.name
                .substringAfterLast('.', "")
                .lowercase()
        ) {
            "png" -> ContentType.Image.PNG
            "webp" -> ContentType.parse("image/webp")
            else -> ContentType.Image.JPEG
        }
}
