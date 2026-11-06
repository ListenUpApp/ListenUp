package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The always-on probes (readout, loaded-book) read narrow Room facts, never the full Book Detail read. */
class PlayerProbeCostTest :
    FunSpec({
        test("loading a book subscribes to Book Detail zero times") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    runCurrent()
                    rig.books.observeBookDetailCalls shouldBe 0
                } finally {
                    rig.close()
                }
            }
        }

        test("a book row deleted under a held session, online, is never fetched back by a probe (no fetch-on-null)") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.network.onlineFlow.value shouldBe true
                    rig.books.removeBookDetail("b1")
                    rig.books.setBooks(emptyList())
                    runCurrent()
                    rig.books.observeBookDetailCalls shouldBe 0
                } finally {
                    rig.close()
                }
            }
        }
    })
