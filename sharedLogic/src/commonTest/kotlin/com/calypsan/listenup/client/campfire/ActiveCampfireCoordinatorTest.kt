package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ActiveCampfireCoordinatorTest :
    FunSpec({
        test("current starts null") {
            ActiveCampfireCoordinator().current.value shouldBe null
        }

        test("set publishes the active campfire, set(null) clears it") {
            val coordinator = ActiveCampfireCoordinator()
            val active = ActiveCampfire(CampfireId("cf-1"), bookId = "book-1", isHost = true)

            coordinator.set(active)
            coordinator.current.value shouldBe active

            coordinator.set(null)
            coordinator.current.value shouldBe null
        }
    })
