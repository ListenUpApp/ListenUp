package com.calypsan.listenup.web

import com.calypsan.listenup.web.nav.Route
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InviteLinkTest :
    FunSpec({

        test("an invite code is taken out of the query") {
            val (code, left) = takeInviteCode(Route.parse("/?invite=TREEHOUSE-42"))

            code shouldBe "TREEHOUSE-42"
            left.query.containsKey("invite") shouldBe false
        }

        test("a URL with no invite is handed back untouched") {
            val route = Route.parse("/book/42?tab=chapters")

            val (code, left) = takeInviteCode(route)

            code shouldBe null
            left.toUrl() shouldBe route.toUrl()
        }

        test("a blank invite is not a code") {
            // `?invite=` is what a half-built link or a stripped redirect looks like. Treating it
            // as a code opens the claim pane on an empty lookup instead of the sign-in form.
            val (code, _) = takeInviteCode(Route.parse("/?invite="))

            code shouldBe null
        }

        test("taking the code leaves every other parameter and the path alone") {
            // The part that would break silently: strip too much and a deep link into a book, or
            // a filter the reader arrived with, quietly disappears from the address bar.
            val (_, left) = takeInviteCode(Route.parse("/book/42?tab=chapters&invite=ABC&sel=9,10"))

            left.segments shouldBe listOf("book", "42")
            left.query["tab"] shouldBe "chapters"
            left.query["sel"] shouldBe "9,10"
            left.toUrl() shouldBe "/book/42?tab=chapters&sel=9,10"
        }
    })
