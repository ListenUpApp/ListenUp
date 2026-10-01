package com.calypsan.listenup.client.data.local.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [SearchDao.searchUnheldBooks] is the play-only search (iOS App Intents, voice): it leaves held books
 * out in SQL, before the `LIMIT`, so a held book ranking near the top can never starve the results.
 */
class SearchDaoUnheldTest :
    FunSpec({
        test("a held book ranking first does not take the only slot of a limited search") {
            withHeldBookDb { db ->
                // bm25 favours the shorter document, so the held book ranks first.
                HeldBookFixture.seedBook(db, "held", title = "Mist")
                HeldBookFixture.seedBook(db, "visible", title = "Mist over the long and winding road")
                HeldBookFixture.hold(db, "held")
                HeldBookFixture.publish(db, "visible")
                db.searchDao().insertBookFts("held", "Mist", null, null, null, null, null, null)
                db.searchDao().insertBookFts(
                    "visible",
                    "Mist over the long and winding road",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                )

                // The premise: the held book is the top hit of the never-stranded search.
                db.searchDao().searchBooks("mist*", limit = 1).map { it.book.id.value } shouldBe listOf("held")

                db.searchDao().searchUnheldBooks("mist*", limit = 1).map { it.book.id.value } shouldBe listOf("visible")
            }
        }

        test("a released book is found again") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "held", title = "Mist")
                HeldBookFixture.hold(db, "held")
                db.searchDao().insertBookFts("held", "Mist", null, null, null, null, null, null)

                db.searchDao().searchUnheldBooks("mist*", limit = 5) shouldBe emptyList()

                HeldBookFixture.applyReleaseEcho(db, "held")

                db.searchDao().searchUnheldBooks("mist*", limit = 5).map { it.book.id.value } shouldBe listOf("held")
            }
        }
    })
