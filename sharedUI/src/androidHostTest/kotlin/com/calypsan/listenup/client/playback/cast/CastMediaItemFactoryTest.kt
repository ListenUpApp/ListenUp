package com.calypsan.listenup.client.playback.cast

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize

class CastMediaItemFactoryTest :
    FunSpec({
        val factory = CastMediaItemFactory()

        val current =
            listOf(
                CastSourceItem(fileId = "f1", title = "Book", artist = "Author", albumTitle = "Series"),
                CastSourceItem(fileId = "f2", title = "Book", artist = "Author", albumTitle = "Series"),
            )

        test("builds one cast track per matched, castable file with absolute URL + MIME + cover") {
            val prepared =
                listOf(
                    CastPreparedFile(fileId = "f1", absoluteUrl = "http://s/a/f1?sig=1", format = "mp3"),
                    CastPreparedFile(fileId = "f2", absoluteUrl = "http://s/a/f2?sig=2", format = "m4b"),
                )
            val result = factory.build(current, prepared, coverUrlAbsolute = "http://s/cover?sig=3")
            result.tracks shouldHaveSize 2
            result.tracks[0].uri shouldBe "http://s/a/f1?sig=1"
            result.tracks[0].mimeType shouldBe "audio/mpeg"
            result.tracks[0].artworkUri shouldBe "http://s/cover?sig=3"
            result.tracks[0].title shouldBe "Book"
            result.droppedUncastable shouldBe 0
            result.droppedUnmatched shouldBe 0
        }

        test("drops un-castable files and counts them") {
            val prepared =
                listOf(
                    CastPreparedFile(fileId = "f1", absoluteUrl = "http://s/a/f1", format = "mp3"),
                    CastPreparedFile(fileId = "f2", absoluteUrl = "http://s/a/f2", format = "wma"),
                )
            val result = factory.build(current, prepared, coverUrlAbsolute = null)
            result.tracks shouldHaveSize 1
            result.tracks[0].fileId shouldBe "f1"
            result.droppedUncastable shouldBe 1
            result.droppedUnmatched shouldBe 0
        }

        test("preserves the order of the currently-loaded items") {
            val prepared =
                listOf(
                    CastPreparedFile(fileId = "f2", absoluteUrl = "u2", format = "mp3"),
                    CastPreparedFile(fileId = "f1", absoluteUrl = "u1", format = "mp3"),
                )
            val result = factory.build(current, prepared, coverUrlAbsolute = null)
            result.tracks.map { it.fileId } shouldBe listOf("f1", "f2")
        }

        test("empty currentItems yields no tracks and no drops") {
            val prepared =
                listOf(
                    CastPreparedFile(fileId = "f1", absoluteUrl = "u1", format = "mp3"),
                )
            val result = factory.build(emptyList(), prepared, coverUrlAbsolute = null)
            result.tracks shouldHaveSize 0
            result.droppedUncastable shouldBe 0
            result.droppedUnmatched shouldBe 0
        }

        test("drops a current item with no matching prepared file and counts it as unmatched") {
            val prepared =
                listOf(
                    CastPreparedFile(fileId = "f1", absoluteUrl = "u1", format = "mp3"),
                )
            val result = factory.build(current, prepared, coverUrlAbsolute = null)
            result.tracks shouldHaveSize 1
            result.tracks[0].fileId shouldBe "f1"
            result.droppedUnmatched shouldBe 1
            result.droppedUncastable shouldBe 0
        }

        test("propagates a null cover as a null artworkUri") {
            val prepared =
                listOf(
                    CastPreparedFile(fileId = "f1", absoluteUrl = "u1", format = "mp3"),
                )
            val result = factory.build(current.take(1), prepared, coverUrlAbsolute = null)
            result.tracks shouldHaveSize 1
            result.tracks[0].artworkUri shouldBe null
        }

        test("distinguishes un-castable and unmatched drops in one build") {
            val items =
                listOf(
                    CastSourceItem(fileId = "f1", title = "Book", artist = "Author", albumTitle = "Series"),
                    CastSourceItem(fileId = "f2", title = "Book", artist = "Author", albumTitle = "Series"),
                    CastSourceItem(fileId = "f3", title = "Book", artist = "Author", albumTitle = "Series"),
                )
            val prepared =
                listOf(
                    CastPreparedFile(fileId = "f1", absoluteUrl = "u1", format = "mp3"),
                    CastPreparedFile(fileId = "f2", absoluteUrl = "u2", format = "wma"),
                    // f3 deliberately absent → unmatched
                )
            val result = factory.build(items, prepared, coverUrlAbsolute = null)
            result.tracks.map { it.fileId } shouldBe listOf("f1")
            result.droppedUncastable shouldBe 1
            result.droppedUnmatched shouldBe 1
        }
    })
