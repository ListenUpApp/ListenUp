package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.BookChapterSource
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4Parser
import com.calypsan.listenup.server.scanner.inference.FileTypeRules
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Env-gated live corpus regression guard for the chapter-synthesis branch.
 *
 * Verifies that real multi-file MP3 audiobooks — where no embedded CHAP
 * frames or sidecar chapters exist — produce
 * [BookChapterSource.SynthesizedFromTracks] from the Analyzer pipeline.
 *
 * **Skipped in CI** — [LIVE_CORPUS_ENV] is unset on shared infrastructure.
 * Run locally:
 *
 * ```
 * LISTENUP_EMBEDDEDMETA_LIVE_DIR=/mnt/Igni/Audiobooks \
 *   ./gradlew :server:test \
 *   --tests "com.calypsan.listenup.server.scanner.pipeline.LiveCorpusAnalyzerTest"
 * ```
 */
class LiveCorpusAnalyzerTest :
    FunSpec({
        val liveDir = System.getenv(LIVE_CORPUS_ENV)

        test("Myst - The Book Of Atrus synthesizes chapters from tracks")
            .config(enabled = liveDir != null) {
                val rel = "Rand Miller, Robyn Miller/Myst - The Book Of Atrus"
                runTest { assertSynthesis(Path.of(liveDir!!), rel) }
            }

        test("The Girl Who Kicked the Hornet's Nest synthesizes chapters from tracks")
            .config(enabled = liveDir != null) {
                val rel = "Steig Larsson/Millenium/Book 3 - The Girl Who Kicked the Hornet's Nest"
                runTest { assertSynthesis(Path.of(liveDir!!), rel) }
            }

        test("Moral Revolution synthesizes chapters from tracks")
            .config(enabled = liveDir != null) {
                val rel = "Kris Valloton/Moral Revolution"
                runTest { assertSynthesis(Path.of(liveDir!!), rel) }
            }
    })

private const val LIVE_CORPUS_ENV = "LISTENUP_EMBEDDEDMETA_LIVE_DIR"

private val liveParser =
    EmbeddedMetadataParser(
        detector = AudioFormatDetector(),
        parsers = listOf(Mp3Parser(), Mp4Parser()),
    )

private suspend fun assertSynthesis(
    rootPath: Path,
    rel: String,
) {
    val bookPath = rootPath.resolve(rel)
    require(bookPath.isDirectory()) { "expected directory: $bookPath" }

    val files =
        bookPath
            .listDirectoryEntries()
            .filter { Files.isRegularFile(it) }
            .map { entry ->
                val name = entry.fileName.toString()
                val ext = name.substringAfterLast('.', "").lowercase()
                FileEntry(
                    relPath = "$rel/$name",
                    name = name,
                    ext = ext,
                    size = Files.size(entry),
                    mtimeMs = Files.getLastModifiedTime(entry).toMillis(),
                    inode = null,
                    fileType = FileTypeRules.classify(name),
                )
            }

    val candidate = CandidateBook(rootRelPath = rel, isFile = false, files = files)
    val analyzer = Analyzer(rootPath, AbsMetadataReader(contractJson), liveParser)
    val book =
        analyzer
            .analyze(flowOf(candidate))
            .toList()
            .single()
            .getOrThrow()

    println(
        "[live-corpus] '${book.title}' tracks=${book.tracks.size} " +
            "chapters=${book.chapters.size} source=${book.chaptersSource} " +
            "first=${book.chapters.firstOrNull()?.title}",
    )

    withClue(
        "book='${book.title}' tracks=${book.tracks.size} " +
            "chapters=${book.chapters.size} source=${book.chaptersSource}",
    ) {
        book.chaptersSource shouldBe BookChapterSource.SynthesizedFromTracks
    }
}
