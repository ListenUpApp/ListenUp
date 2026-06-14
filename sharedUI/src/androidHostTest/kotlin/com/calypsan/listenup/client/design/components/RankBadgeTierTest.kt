package com.calypsan.listenup.client.design.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RankBadgeTierTest :
    FunSpec({
        test("rank 1 is the gold tier") {
            rankTier(1) shouldBe RankTier.Gold
        }

        test("rank 2 is the silver tier") {
            rankTier(2) shouldBe RankTier.Silver
        }

        test("rank 3 is the bronze tier") {
            rankTier(3) shouldBe RankTier.Bronze
        }

        test("ranks off the podium are the neutral tier") {
            rankTier(4) shouldBe RankTier.Neutral
            rankTier(10) shouldBe RankTier.Neutral
        }
    })
