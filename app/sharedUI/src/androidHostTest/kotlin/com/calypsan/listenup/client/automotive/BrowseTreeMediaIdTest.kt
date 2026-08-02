package com.calypsan.listenup.client.automotive

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Media-id round-tripping for the browse tree.
 *
 * `PlaybackService.onSetMediaItems` runs [BrowseTree.extractBookId] over every media id a
 * controller sets, including the queue the app's own player builds — where ids are audio
 * file ids, not browse ids. A false match there would rebuild the queue and override the
 * position the app just resolved, so "what is *not* a book id" is the load-bearing half.
 */
class BrowseTreeMediaIdTest :
    FunSpec({

        test("a browse book id round-trips") {
            BrowseTree.extractBookId(BrowseTree.bookId("book-1")) shouldBe "book-1"
        }

        test("an audio file id is not a book id") {
            BrowseTree.extractBookId("af-3f2a9c1e") shouldBe null
        }

        test("the other browse prefixes are not book ids") {
            BrowseTree.extractBookId("${BrowseTree.PREFIX_SERIES}series-1") shouldBe null
            BrowseTree.extractBookId("${BrowseTree.PREFIX_AUTHOR}author-1") shouldBe null
            BrowseTree.extractBookId("${BrowseTree.PREFIX_COLLECTION}collection-1") shouldBe null
        }
    })
