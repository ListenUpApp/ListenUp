package com.calypsan.listenup.client.features.bookdetail.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * The breathing glow behind a reader who is listening now: one 2000 ms cycle, 1600 ms of breath (scale 1.0 to 1.7,
 * alpha 0.45 to 0, emphasized decelerate) and a 400 ms rest; held still at scale 1.35, alpha 0.22 when motion is off.
 */
class LiveGlowTest :
    FunSpec({
        test("a breath starts at the avatar's own size, at its strongest") {
            liveGlowPoseAt(0f) shouldBe LiveGlowPose(scale = 1f, alpha = 0.45f)
        }

        test("the breath ends at 1.7 times the avatar, faded out, and rests there until the cycle turns") {
            listOf(1_600f, 1_800f, 1_999f).forEach { cycleMs ->
                val pose = liveGlowPoseAt(cycleMs)
                pose.scale shouldBe (1.7f plusOrMinus 0.0001f)
                pose.alpha shouldBe (0f plusOrMinus 0.0001f)
            }
        }

        test("it decelerates: halfway through the breath it is already well past halfway out") {
            val half = liveGlowPoseAt(800f)
            half.scale shouldBeGreaterThan 1.35f
            (0.45f - half.alpha) shouldBeGreaterThan 0.225f
        }

        test("held still, the glow sits at 1.35 times the avatar and 22 percent") {
            liveGlowPoseAt(STILL_GLOW) shouldBe LiveGlowPose.Still
            LiveGlowPose.Still shouldBe LiveGlowPose(scale = 1.35f, alpha = 0.22f)
        }
    })
