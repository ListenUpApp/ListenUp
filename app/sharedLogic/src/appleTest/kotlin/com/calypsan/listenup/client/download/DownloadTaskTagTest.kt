package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The tag a background download task carries. It outlives the process inside `nsurlsessiond`, so it
 * must survive a relaunch and a reinstall: it names the file, never a path in a container that can
 * move.
 */
class DownloadTaskTagTest :
    FunSpec({
        test("a tag survives the round trip through the task's description") {
            val tag = DownloadTaskTag(bookId = "book-1", audioFileId = "file-1", filename = "Chapter 01.m4b")

            DownloadTaskTag.parse(tag.encode()) shouldBe tag
        }

        test("the encoded tag is versioned and carries no path") {
            DownloadTaskTag("book-1", "file-1", "a.m4b").encode() shouldBe "v2|book-1|file-1|a.m4b"
        }

        test("a filename with the separator in it survives") {
            val tag = DownloadTaskTag("book-1", "file-1", "Part 1 | Part 2.mp3")

            DownloadTaskTag.parse(tag.encode()) shouldBe tag
        }

        test("a tag from the foreground-session build, with its absolute path, names the same file") {
            DownloadTaskTag.parse("book-1|file-1|a.m4b|/var/mobile/Containers/Data/x/Documents/audiobooks/a.m4b") shouldBe
                DownloadTaskTag("book-1", "file-1", "a.m4b")
        }

        test("anything else is not a tag") {
            DownloadTaskTag.parse(null).shouldBeNull()
            DownloadTaskTag.parse("").shouldBeNull()
            DownloadTaskTag.parse("v2|book-1").shouldBeNull()
            DownloadTaskTag.parse("v2||file-1|a.m4b").shouldBeNull()
            DownloadTaskTag.parse("not a tag").shouldBeNull()
        }
    })
