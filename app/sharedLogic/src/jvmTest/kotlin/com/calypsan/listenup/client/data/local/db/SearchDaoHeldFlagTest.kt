package com.calypsan.listenup.client.data.local.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Search is the never-stranded path to a held book: it still finds it, and says it is held. The
 * flag is computed in the same query from [HELD_BOOK_IDS_SQL], so it cannot disagree with the inbox.
 */
class SearchDaoHeldFlagTest :
    FunSpec({
        test("searchBooks keeps a held book and flags it, and the flag clears with the release") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "visible", title = "Mistborn")
                HeldBookFixture.seedBook(db, "held", title = "Mistwraith")
                HeldBookFixture.publish(db, "visible")
                HeldBookFixture.hold(db, "held")
                db.searchDao().insertBookFts("visible", "Mistborn", null, null, null, null, null, null)
                db.searchDao().insertBookFts("held", "Mistwraith", null, null, null, null, null, null)

                db.searchDao().searchBooks("mist*", limit = 10).associate { it.book.id.value to it.isHeld } shouldBe
                    mapOf("visible" to false, "held" to true)

                HeldBookFixture.applyReleaseEcho(db, "held")

                db.searchDao().searchBooks("mist*", limit = 10).associate { it.book.id.value to it.isHeld } shouldBe
                    mapOf("visible" to false, "held" to false)
            }
        }
    })
