package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.TimeOfDay
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Home's greeting and words; the section order is the shared `homeBlocks`, specced in `:app:sharedLogic`. */
class HomeLayoutTest :
    FunSpec({
        test("Home's words live in one object and mirror the catalog's English") {
            HomeWords.EVERYTHING_IN_PROGRESS shouldBe "Everything in progress"
            HomeWords.WELCOME_HEADLINE shouldBe "Pick something new"
            HomeWords.OFFLINE_NOTICE shouldBe "Can't reach your server."
            HomeWords.BUILDING_TITLE shouldBe "Building your library"
            HomeWords.arrivingTitle(done = 412, total = 1_280) shouldBe "Building your library · 412 of 1,280 books"
            HomeWords.alsoFinished(listOf("Maya"), more = 0) shouldBe "Maya finished it too"
            HomeWords.alsoFinished(listOf("Maya", "Tom"), more = 0) shouldBe "Maya and Tom finished it too"
            HomeWords.alsoFinished(listOf("Maya"), more = 2) shouldBe "Maya and 2 more finished it too"
            HomeWords.alsoFinished(listOf("Maya", "Tom"), more = 3) shouldBe "Maya, Tom and 3 more finished it too"
            HomeWords.alsoFinished(emptyList(), more = 3) shouldBe null
        }

        test("a new listener is welcomed by name") {
            homeGreeting(TimeOfDay.Evening, "Simon", isWelcome = true) shouldBe "Welcome, Simon"
        }
    })
