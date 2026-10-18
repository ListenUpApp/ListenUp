package com.calypsan.listenup.web.features.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The Home session hands the finish-card actions through (memory "web actions are unwired"); PR 4 draws them. */
class HomeStoreTest :
    FunSpec({
        test("a fixed Home hands dismiss and rate straight through") {
            var dismissed: String? = null
            var rated: Pair<String, Int>? = null
            val session =
                fixedHome(
                    state = readyHome(),
                    onDismissFinished = { dismissed = it },
                    onRateFinished = { id, halfStars -> rated = id to halfStars },
                )()

            session.onDismissFinished("b1")
            session.onRateFinished("b1", 8)

            dismissed shouldBe "b1"
            rated shouldBe ("b1" to 8)
        }

        test("a fixed Home hands retryConnection straight through") {
            var retried = 0
            val session = fixedHome(state = readyHome(), onRetryConnection = { retried++ })()

            session.onRetryConnection()

            retried shouldBe 1
        }
    })
