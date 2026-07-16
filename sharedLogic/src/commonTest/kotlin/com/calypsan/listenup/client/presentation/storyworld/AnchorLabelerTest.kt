package com.calypsan.listenup.client.presentation.storyworld

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds

private fun chapter(
    id: String,
    title: String,
    startTime: Long,
    duration: Long,
): Chapter = Chapter(id = id, title = title, duration = duration, startTime = startTime)

class AnchorLabelerTest :
    FunSpec({

        test("null bookLabel yields AlwaysVisible regardless of chapters/position") {
            val label = AnchorLabeler.label(bookLabel = null, chapters = emptyList(), positionMs = 5_000L)

            label shouldBe AnchorLabel.AlwaysVisible
        }

        test("null positionMs yields BookOnly") {
            val label = AnchorLabeler.label(bookLabel = "The Way of Kings", chapters = emptyList(), positionMs = null)

            label shouldBe AnchorLabel.BookOnly("The Way of Kings")
        }

        test("zero positionMs yields Beginning, even when chapters exist") {
            val chapters = listOf(chapter("c1", "Prologue", startTime = 0L, duration = 60_000L))

            val label = AnchorLabeler.label(bookLabel = "The Way of Kings", chapters = chapters, positionMs = 0L)

            label shouldBe AnchorLabel.Beginning("The Way of Kings")
        }

        test("positionMs exactly at a chapter's start boundary maps to that chapter") {
            val chapters =
                listOf(
                    chapter("c1", "Chapter One", startTime = 0L, duration = 60_000L),
                    chapter("c2", "Chapter Two", startTime = 60_000L, duration = 60_000L),
                )

            val label = AnchorLabeler.label(bookLabel = "Book", chapters = chapters, positionMs = 60_000L)

            label shouldBe AnchorLabel.AtChapter("Book", "Chapter Two")
        }

        test("positionMs mid-chapter maps to the containing chapter") {
            val chapters =
                listOf(
                    chapter("c1", "Chapter One", startTime = 0L, duration = 60_000L),
                    chapter("c2", "Chapter Two", startTime = 60_000L, duration = 60_000L),
                )

            val label = AnchorLabeler.label(bookLabel = "Book", chapters = chapters, positionMs = 90_000L)

            label shouldBe AnchorLabel.AtChapter("Book", "Chapter Two")
        }

        test("positionMs past the last chapter's end falls through to AtTime") {
            val chapters =
                listOf(
                    chapter("c1", "Chapter One", startTime = 0L, duration = 60_000L),
                    chapter("c2", "Chapter Two", startTime = 60_000L, duration = 60_000L),
                )
            // Last chapter ends at 120_000L; 150_000L is past it (e.g. stale/short chapter metadata).
            val positionMs = 150_000L

            val label = AnchorLabeler.label(bookLabel = "Book", chapters = chapters, positionMs = positionMs)

            label.shouldBeInstanceOf<AnchorLabel.AtTime>()
            (label as AnchorLabel.AtTime).formattedTime shouldBe DurationFormatter.hoursMinutes(positionMs.milliseconds)
        }

        test("empty chapters list falls through to AtTime") {
            val positionMs = 42_000L

            val label = AnchorLabeler.label(bookLabel = "Book", chapters = emptyList(), positionMs = positionMs)

            label shouldBe AnchorLabel.AtTime("Book", DurationFormatter.hoursMinutes(positionMs.milliseconds))
        }

        test("single-chapter book maps any in-range position to that one chapter") {
            val chapters = listOf(chapter("c1", "Only Chapter", startTime = 0L, duration = 3_600_000L))

            val label = AnchorLabeler.label(bookLabel = "Book", chapters = chapters, positionMs = 1_800_000L)

            label shouldBe AnchorLabel.AtChapter("Book", "Only Chapter")
        }

        test("single-chapter book: position past its end falls through to AtTime") {
            val chapters = listOf(chapter("c1", "Only Chapter", startTime = 0L, duration = 3_600_000L))
            val positionMs = 3_700_000L

            val label = AnchorLabeler.label(bookLabel = "Book", chapters = chapters, positionMs = positionMs)

            label shouldBe AnchorLabel.AtTime("Book", DurationFormatter.hoursMinutes(positionMs.milliseconds))
        }
    })
