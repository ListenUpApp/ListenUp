package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Tests for [computeInSampleSize] — the downsample factor a notification large icon needs.
 *
 * The bug that motivates this: notification artwork was decoded at the cover provider's native
 * resolution (routinely 1500–2400 px on an edge) and several of those bitmaps were held in an
 * LRU, so a media notification could pin well over a hundred megabytes of ARGB_8888 pixels — a
 * notification large icon is drawn at a few tens of dp.
 */
class NotificationArtworkTest :
    FunSpec({

        // src width, src height, cap, expected sample factor.
        val cases =
            listOf(
                Triple(2400, 2400, 512) to 8,
                Triple(1500, 1500, 512) to 4,
                Triple(1000, 600, 512) to 2,
                Triple(512, 512, 512) to 1,
                Triple(513, 100, 512) to 2,
            )

        cases.forEach { (input, expected) ->
            val (width, height, cap) = input
            test("a ${width}x$height image capped at $cap samples by $expected") {
                computeInSampleSize(width, height, cap) shouldBe expected
            }

            test("a ${width}x$height image capped at $cap ends within the cap") {
                maxOf(width, height) / computeInSampleSize(width, height, cap) shouldBeLessThanOrEqual cap
            }
        }

        test("bounds that failed to decode do not spin the halving loop") {
            computeInSampleSize(0, 0, 512) shouldBe 1
        }

        test("a non-positive cap does not spin the halving loop") {
            computeInSampleSize(2400, 2400, 0) shouldBe 1
        }
    })
