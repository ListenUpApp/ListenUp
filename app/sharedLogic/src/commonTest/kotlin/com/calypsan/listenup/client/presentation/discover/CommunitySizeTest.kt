package com.calypsan.listenup.client.presentation.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CommunitySizeTest :
    FunSpec({
        test("an empty roster has not synced yet, so the size is unknown") {
            communitySizeOf(emptyList(), currentUserId = "me") shouldBe CommunitySize.Unknown
        }

        test("a roster of only you is just you") {
            communitySizeOf(listOf("me"), currentUserId = "me") shouldBe CommunitySize.JustYou
        }

        test("anyone else on the roster means others") {
            communitySizeOf(listOf("me", "ada"), currentUserId = "me") shouldBe CommunitySize.Others
            communitySizeOf(listOf("ada"), currentUserId = "me") shouldBe CommunitySize.Others
        }
    })
