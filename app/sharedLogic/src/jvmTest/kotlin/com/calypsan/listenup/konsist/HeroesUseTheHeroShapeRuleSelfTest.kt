package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [handRolledHeroShapeLines], so the rule is known to fire before it is trusted. */
class HeroesUseTheHeroShapeRuleSelfTest :
    FunSpec({
        test("a bottom-rounded shape is reported by line, on one line or spread over several") {
            val source =
                """
                |shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp),
                |Modifier.clip(
                |    RoundedCornerShape(
                |        bottomStart = 40.dp,
                |        bottomEnd = 40.dp,
                |    ),
                |)
                """.trimMargin()
            handRolledHeroShapeLines(source) shouldBe listOf(1, 3)
        }

        test("the token, top-led geometry and percent avatar shapes are not reported") {
            val source =
                """
                |shape = ContentShapes.hero,
                |RoundedCornerShape(topStart = 40.dp, topEnd = 120.dp, bottomEnd = 80.dp, bottomStart = 160.dp)
                |RoundedCornerShape(bottomStartPercent = 54, topStartPercent = 46)
                |RoundedCornerShape(24.dp)
                """.trimMargin()
            handRolledHeroShapeLines(source) shouldBe emptyList()
        }

        test("a shape quoted in a comment is not reported") {
            val source =
                """
                |// was RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp)
                |/* RoundedCornerShape(bottomStart = 32.dp) */
                """.trimMargin()
            handRolledHeroShapeLines(source) shouldBe emptyList()
        }
    })
