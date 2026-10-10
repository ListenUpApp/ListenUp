package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Download rows store a path relative to `Documents`; the container that holds `Documents` can move. */
class AppleDownloadPathsTest :
    FunSpec({

        val docs = "/var/mobile/Containers/Data/Application/AAAA-1111/Documents"
        val oldContainer = "/var/mobile/Containers/Data/Application/ZZZZ-9999/Documents"

        test("an absolute download path is stored relative to Documents") {
            storedDownloadPath("$docs/audiobooks/book-1/file-1_a.m4b") shouldBe "audiobooks/book-1/file-1_a.m4b"
        }

        test("a path with no audiobooks segment is stored unchanged") {
            storedDownloadPath("/tmp/a.m4b") shouldBe "/tmp/a.m4b"
        }

        test("a relative stored path resolves under the current Documents") {
            resolveDownloadPath("audiobooks/book-1/file-1_a.m4b", docs) shouldBe "$docs/audiobooks/book-1/file-1_a.m4b"
        }

        test("an absolute path from an old container is re-anchored at audiobooks") {
            resolveDownloadPath("$oldContainer/audiobooks/book-1/file-1_a.m4b", docs) shouldBe
                "$docs/audiobooks/book-1/file-1_a.m4b"
        }

        test("an absolute path already in the current container is unchanged in effect") {
            val path = "$docs/audiobooks/book-1/file-1_a.m4b"
            resolveDownloadPath(path, docs) shouldBe path
        }

        test("an absolute path with no audiobooks segment is returned as it is") {
            resolveDownloadPath("/tmp/a.m4b", docs) shouldBe "/tmp/a.m4b"
        }

        test("a file name that contains the word audiobooks does not confuse the anchor") {
            val path = "$docs/audiobooks/book-1/file-1_my audiobooks vol 1.m4b"
            resolveDownloadPath(storedDownloadPath(path), docs) shouldBe path
        }

        test("round trip") {
            val path = "$docs/audiobooks/book-1/file-1_a.m4b"
            resolveDownloadPath(storedDownloadPath(path), docs) shouldBe path
        }
    })
