package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs, so the rule is known to fire before it is trusted to stay quiet. */
class CoroutineSafetySuppressionsAreReviewedRuleSelfTest :
    FunSpec({
        test("each of the three detectors is counted, singly and in a list") {
            val source =
                """
                |@Suppress("SuspendFunSwallowedCancellation")
                |suspend fun a() = Unit
                |
                |@Suppress("InjectDispatcher", "MagicNumber")
                |val b = 1
                |
                |@Suppress("MagicNumber", "GlobalCoroutineUsage")
                |val c = 2
                """.trimMargin()
            coroutineSafetySuppressionCount(source) shouldBe 3
        }

        test("other suppressions and mentions in prose are not counted") {
            val source =
                """
                |// SuspendFunSwallowedCancellation is a detekt rule
                |@Suppress("MagicNumber")
                |val a = 1
                """.trimMargin()
            coroutineSafetySuppressionCount(source) shouldBe 0
        }

        test("test and desktop source sets are outside the guard") {
            isReviewedProductionPath("server/src/commonMain/kotlin/A.kt") shouldBe true
            isReviewedProductionPath("app/sharedLogic/src/androidMain/kotlin/A.kt") shouldBe true
            isReviewedProductionPath("server/src/jvmTest/kotlin/A.kt") shouldBe false
            isReviewedProductionPath("app/sharedLogic/src/androidHostTest/kotlin/A.kt") shouldBe false
            isReviewedProductionPath("app/sharedLogic/src/desktopMain/kotlin/A.kt") shouldBe false
            isReviewedProductionPath("app/desktopApp/src/main/kotlin/A.kt") shouldBe false
        }
    })
