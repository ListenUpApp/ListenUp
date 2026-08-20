package com.calypsan.listenup.server.seed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * The seed library was 100% MP3, which every browser decodes — so with capability negotiation
 * live, no seed book would ever take the transcode path and the browser proof would pass while
 * exercising nothing. This book exists to be undecodable.
 */
class SeedEac3BookTest :
    FunSpec({
        test("the descriptor contains a book whose track is E-AC-3") {
            val book = SeedLibraryDescriptor.BOOKS.single { it.title == EAC3_BOOK_TITLE }
            book.tracks.map { it.fileName } shouldContain "01.m4b"
        }

        test("generating it produces a real ec-3 sample entry") {
            val root = createTempDirectory("seed-eac3-")
            val book = SeedLibraryDescriptor.BOOKS.single { it.title == EAC3_BOOK_TITLE }
            SeedLibraryGenerator.generate(root)

            val track = root.resolve(book.folderPath).resolve("01.m4b")
            val tag =
                ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "stream=codec_tag_string",
                    "-of",
                    "default=nw=1:nk=1",
                    track.toString(),
                ).start().inputStream.bufferedReader().readText().trim()

            tag shouldBe "ec-3"
        }
    })

private const val EAC3_BOOK_TITLE = "The Undecodable Hour"
