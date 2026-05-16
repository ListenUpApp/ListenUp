package com.calypsan.listenup.server.cover

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.services.BookRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

/**
 * Serves a book's cover image bytes for `GET /api/v1/books/{id}/cover`.
 *
 * Concentrates the cover-serving logic — path resolution, embedded-artwork
 * extraction, content-type derivation — into one focused collaborator so the
 * book route stays mechanical glue. Two cover kinds (see [CoverInfo]):
 *
 *  - **Filesystem** — the image bytes are read straight off disk; the
 *    content-type comes from the file extension.
 *  - **Embedded** — artwork is extracted from the audio file via
 *    [EmbeddedMetadataParser] and cached in [cache]; the content-type comes
 *    from the artwork's own MIME field.
 *
 * Every failure mode that isn't a successful image — missing book, missing
 * file, unparseable audio, artwork-less audio — resolves to a plain 404. The
 * route never 500s on an absent or unreadable cover.
 *
 * The constructor is `internal` — it depends on the `internal`
 * [EmbeddedMetadataParser], so only the `server` module's Koin wiring builds
 * an instance; the class itself stays public so route signatures can name it.
 *
 * @param repository resolves a book id to its [CoverInfo].
 * @param cache the LRU cache for extracted embedded artwork.
 * @param parser the embedded-metadata parser used to extract artwork.
 */
class CoverResponder internal constructor(
    private val repository: BookRepository,
    private val cache: EmbeddedCoverCache,
    private val parser: EmbeddedMetadataParser,
) {
    /** Resolves [id]'s cover and writes the image bytes (or a 404) to [call]. */
    suspend fun respondCover(
        call: ApplicationCall,
        id: BookId,
    ) {
        when (val info = repository.coverInfo(id)) {
            null -> call.respond(HttpStatusCode.NotFound)
            is CoverInfo.Filesystem -> respondFilesystem(call, info.path)
            is CoverInfo.Embedded -> respondEmbedded(call, id, info.audioFilePath)
        }
    }

    private suspend fun respondFilesystem(
        call: ApplicationCall,
        path: Path,
    ) {
        val bytes =
            withContext(Dispatchers.IO) {
                if (!Files.isRegularFile(path)) null else Files.readAllBytes(path)
            }
        if (bytes == null) {
            // The DB still records a filesystem cover, but the file vanished
            // since the scan — a 404, not a 500.
            call.respond(HttpStatusCode.NotFound)
            return
        }
        call.respondBytes(bytes, contentTypeForExtension(path))
    }

    private suspend fun respondEmbedded(
        call: ApplicationCall,
        id: BookId,
        audioFilePath: Path,
    ) {
        val artwork =
            cache.getOrCompute(id) {
                when (val result = parser.parse(kotlinx.io.files.Path(audioFilePath.toString()))) {
                    is AppResult.Success -> {
                        result.data.artwork
                    }

                    is AppResult.Failure -> {
                        log.warn { "embedded cover extraction failed for $id: ${result.error.code}" }
                        null
                    }
                }
            }
        if (artwork == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        call.respondBytes(
            artwork.bytes,
            runCatching { ContentType.parse(artwork.mime) }.getOrDefault(ContentType.Image.JPEG),
        )
    }

    /**
     * Maps a cover image file's extension to its [ContentType]. Unknown
     * extensions fall back to `image/jpeg` — the scanner only ingests
     * `png`/`jpg`/`jpeg`/`webp`, so this is defensive.
     */
    private fun contentTypeForExtension(path: Path): ContentType =
        when (
            path.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        ) {
            "png" -> ContentType.Image.PNG
            "webp" -> ContentType.parse("image/webp")
            else -> ContentType.Image.JPEG
        }
}
