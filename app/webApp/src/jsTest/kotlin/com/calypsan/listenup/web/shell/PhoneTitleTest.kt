package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.phoneTitleFor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * What the phone header names: "Library" across all four Library sections, as Android's header does, "Discover" on
 * Discover, and nothing on Home (its greeting is its title) or a page deeper in.
 */
class PhoneTitleTest :
    FunSpec({
        test("every Library section is titled Library") {
            listOf("/library", "/library/series", "/library/contributors", "/library/contributors?role=narrator")
                .map { phoneTitleFor(Route.parse(it)) } shouldBe List(4) { "Library" }
        }

        test("Discover is titled Discover; Home and deeper pages carry no title") {
            phoneTitleFor(Route.parse("/discover")) shouldBe "Discover"
            phoneTitleFor(Route.parse("/")) shouldBe null
            phoneTitleFor(Route.parse("/book/b1")) shouldBe null
            phoneTitleFor(Route.parse("/library/series/s1")) shouldBe null
        }
    })
