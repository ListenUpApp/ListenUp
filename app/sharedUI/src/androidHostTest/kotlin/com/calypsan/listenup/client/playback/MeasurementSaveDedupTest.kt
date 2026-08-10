package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the dedup rule that decides whether a pause persists a refined loudness measurement.
 *
 * The meter refines continuously while a book plays, so every pause offers a slightly different
 * reading. Without this guard a listener who pauses often would enqueue a sync write per pause for
 * differences nobody can hear.
 */
class MeasurementSaveDedupTest :
    FunSpec({

        test("the first measurement always saves") {
            shouldSaveMeasurement(measured = -3.2f, lastSaved = null) shouldBe true
        }

        test("a reading within the jitter floor of the last save is skipped") {
            shouldSaveMeasurement(measured = -3.25f, lastSaved = -3.2f) shouldBe false
        }

        test("a reading beyond the jitter floor saves") {
            shouldSaveMeasurement(measured = -3.5f, lastSaved = -3.2f) shouldBe true
        }
    })
