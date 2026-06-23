package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Book-level metadata for **multi-file books** — a directory where each file is a
 * track/chapter (e.g. an audiobook split into 38 per-chapter M4Bs).
 *
 * Such a book must become ONE book whose title comes from the **folder** (not the first
 * track's embedded title, which is a chapter title) and whose tracks each carry their own
 * duration (so the book's total duration sums every track, not just the primary).
 */
class AnalyzerMultiFileBookTest :
    FunSpec({
        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParser =
            EmbeddedMetadataParser(
                detector = AudioFormatDetector(),
                parsers = listOf(Mp3Parser()),
            )

        test("multi-file book takes its title from the album tag, not the per-track title or the [N] folder") {
            audioLibrary {}.use { fixture ->
                runTest {
                    // Real-world shape: the folder carries a series-index prefix ("[1] ..."), each file's
                    // TIT2 is a chapter title, and the book title lives in the album tag (TALB). The album
                    // must win — matching Audiobookshelf — over both the chapter title and the raw folder.
                    val rel = "Joaquin Baldwin/[1] Wolf of Withervale"
                    val t1 =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TRCK", "1")
                                textFrame("TALB", "Wolf of Withervale")
                                textFrame("TIT2", "Opening Credits")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val t2 =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TRCK", "2")
                                textFrame("TALB", "Wolf of Withervale")
                                textFrame("TIT2", "Prelude: The Wounded Fox")
                            }
                            mpegFrames(durationSeconds = 2)
                        }
                    val p1 = fixture.root.writeAudio("$rel/01.mp3", t1)
                    val p2 = fixture.root.writeAudio("$rel/02.mp3", t2)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    trackEntry("$rel/01.mp3", Files.size(p1)),
                                    trackEntry("$rel/02.mp3", Files.size(p2)),
                                ),
                        )

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Wolf of Withervale"
                }
            }
        }

        test("multi-file book with no album tag falls back to the folder, ignoring the per-track title") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Some Audiobook"
                    val t1 =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TRCK", "1")
                                textFrame("TIT2", "Chapter One")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val t2 =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TRCK", "2")
                                textFrame("TIT2", "Chapter Two")
                            }
                            mpegFrames(durationSeconds = 2)
                        }
                    val p1 = fixture.root.writeAudio("$rel/01.mp3", t1)
                    val p2 = fixture.root.writeAudio("$rel/02.mp3", t2)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    trackEntry("$rel/01.mp3", Files.size(p1)),
                                    trackEntry("$rel/02.mp3", Files.size(p2)),
                                ),
                        )

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Some Audiobook"
                }
            }
        }

        test("multi-file book records each track's own duration") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Multi"
                    val t1 =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TRCK", "1") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val t2 =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TRCK", "2") }
                            mpegFrames(durationSeconds = 3)
                        }
                    val p1 = fixture.root.writeAudio("$rel/01.mp3", t1)
                    val p2 = fixture.root.writeAudio("$rel/02.mp3", t2)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    trackEntry("$rel/01.mp3", Files.size(p1)),
                                    trackEntry("$rel/02.mp3", Files.size(p2)),
                                ),
                        )

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.tracks.size shouldBe 2
                    book.tracks.all { (it.durationMs ?: 0L) > 0L } shouldBe true
                }
            }
        }
    })

private fun trackEntry(
    relPath: String,
    size: Long = 0,
): FileEntry =
    FileEntry(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        ext = relPath.substringAfterLast('.', "").lowercase(),
        size = size,
        mtimeMs = 0,
        inode = null,
        fileType = FileType.AUDIO,
    )

private fun Path.writeAudio(
    relPath: String,
    bytes: ByteArray,
): Path {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
