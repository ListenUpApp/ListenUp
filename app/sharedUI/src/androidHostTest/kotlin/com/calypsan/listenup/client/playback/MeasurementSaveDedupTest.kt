package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val PAUSED_BOOK = BookId("book-being-paused")
private val PREVIOUS_BOOK = BookId("book-just-left")

/**
 * Pins the two reasons a pause declines to persist a refined loudness measurement: the reading has
 * not moved, or the meter that produced it is not scoped to the book being saved.
 *
 * The meter refines continuously while a book plays, so every pause offers a slightly different
 * reading. Without the jitter floor a listener who pauses often would enqueue a sync write per
 * pause for differences nobody can hear. Without the book check, a pause landing in the window
 * between a book change and the meter's reset would file the outgoing book's loudness under the
 * incoming book's id.
 */
class MeasurementSaveDedupTest :
    FunSpec({

        test("the first measurement always saves") {
            shouldSaveMeasurement(
                measured = -3.2f,
                lastSaved = null,
                meterBookId = PAUSED_BOOK,
                bookId = PAUSED_BOOK,
            ) shouldBe true
        }

        test("a reading within the jitter floor of the last save is skipped") {
            shouldSaveMeasurement(
                measured = -3.25f,
                lastSaved = -3.2f,
                meterBookId = PAUSED_BOOK,
                bookId = PAUSED_BOOK,
            ) shouldBe false
        }

        test("a reading beyond the jitter floor saves") {
            shouldSaveMeasurement(
                measured = -3.5f,
                lastSaved = -3.2f,
                meterBookId = PAUSED_BOOK,
                bookId = PAUSED_BOOK,
            ) shouldBe true
        }

        test("a reading from a meter still scoped to the previous book is skipped") {
            shouldSaveMeasurement(
                measured = -3.5f,
                lastSaved = null,
                meterBookId = PREVIOUS_BOOK,
                bookId = PAUSED_BOOK,
            ) shouldBe false
        }

        test("a reading from a meter not yet scoped to any book is skipped") {
            shouldSaveMeasurement(
                measured = -3.5f,
                lastSaved = null,
                meterBookId = null,
                bookId = PAUSED_BOOK,
            ) shouldBe false
        }
    })
