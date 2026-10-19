package com.calypsan.listenup.client.presentation.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CommunitySizeTest :
    FunSpec({
        test("a roster that has not been pulled is unknown, even if your own row is already here") {
            communitySizeOf(rosterUserIds = null, currentUserId = "me") shouldBe CommunitySize.Unknown
        }

        test("a pulled but empty roster is still unknown: a signed-in reader always has their own row") {
            communitySizeOf(rosterUserIds = emptyList(), currentUserId = "me") shouldBe CommunitySize.Unknown
        }

        test("a roster of only you is just you") {
            communitySizeOf(listOf("me"), currentUserId = "me") shouldBe CommunitySize.JustYou
        }

        test("anyone else on the roster means others") {
            communitySizeOf(listOf("me", "ada"), currentUserId = "me") shouldBe CommunitySize.Others
            communitySizeOf(listOf("ada"), currentUserId = "me") shouldBe CommunitySize.Others
        }
    })
