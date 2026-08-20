package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.testing.FfmpegTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * Every other seed track is MP3 or AAC, which Chrome, Firefox and Safari all play directly — so
 * with capability negotiation live, no seed book would ever take the transcode path and the
 * browser proof would pass while exercising nothing. This book exists to be undecodable.
 */
class SeedEac3BookTest :
    FunSpec({
        test("the descriptor contains a book whose track is E-AC-3") {
            val book = SeedLibraryDescriptor.BOOKS.single { it.title == EAC3_BOOK_TITLE }
            book.tracks.map { it.fileName } shouldContain "book.m4b"
            book.tracks.single().codecOverride shouldBe "eac3"
        }

        test("generating it produces a real ec-3 sample entry")
            .config(enabled = FfmpegTestSupport.isAvailable) {
                val root = createTempDirectory("seed-eac3-").also { it.toFile().deleteOnExit() }
                val book = SeedLibraryDescriptor.BOOKS.single { it.title == EAC3_BOOK_TITLE }
                SeedLibraryGenerator.generate(root)

                val track = root.resolve(book.folderPath).resolve("book.m4b")
                val ffprobe = requireNotNull(FfmpegTestSupport.ffprobe) { "gated by isAvailable" }
                val tag =
                    ProcessBuilder(
                        ffprobe,
                        "-v",
                        "error",
                        "-show_entries",
                        "stream=codec_tag_string",
                        "-of",
                        "default=nw=1:nk=1",
                        track.toString(),
                    ).redirectErrorStream(true)
                        .start()
                        .inputStream
                        .bufferedReader()
                        .readText()
                        .trim()

                tag shouldBe "ec-3"
            }
    })

private const val EAC3_BOOK_TITLE = "The Undecodable Hour"
