package com.calypsan.listenup.client.presentation.chaptereditor.timeline

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.withDerivedDurations
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val BOOK_MS = 600_000L

/**
 * [TimelineLane] — the detail lane's interactive state, shared by all three clients.
 *
 * Android, iOS and web each translate their own gestures (touch drag, pointer drag, pinch, wheel)
 * into these calls and draw what comes back, so "how far does a drag move a boundary" and "what
 * does one drag leave on the undo stack" have one answer, pinned here, instead of three.
 */
class TimelineLaneTest :
    FunSpec({

        // 1000px over the first 100s: 100ms per pixel, so the arithmetic reads by eye.
        fun lane() = TimelineLane(TimelineGeometry(windowStartMs = 0L, windowEndMs = 100_000L, widthPx = 1_000f))

        fun chapters(vararg starts: Long): List<Chapter> =
            starts
                .mapIndexed { i, s -> Chapter(id = "c$i", title = "Chapter $i", duration = 0L, startTime = s) }
                .withDerivedDurations(BOOK_MS)

        val set = chapters(0L, 20_000L, 50_000L)

        fun markers(locked: Set<String> = emptySet()) =
            set.mapIndexed { i, c ->
                TimelineChapter(id = c.id, number = i + 1, startMs = c.startTime, locked = c.id in locked)
            }

        test("a press on a boundary grabs it; a press in open lane grabs nothing") {
            lane().grabbed(xPx = 502f, markers = markers()).drag?.chapterId shouldBe "c2"
            lane().grabbed(xPx = 350f, markers = markers()).drag.shouldBeNull()
        }

        test("a locked boundary cannot be dragged, because the lock means the same however it is moved") {
            lane().grabbed(xPx = 502f, markers = markers(locked = setOf("c2"))).drag.shouldBeNull()
        }

        test("a drag previews the boundary where it would land, and leaves the others alone") {
            val dragging = lane().grabbed(502f, markers()).dragged(dxPx = 30f, pulledDp = 0f)

            dragging.preview(set, BOOK_MS).map { it.startTime } shouldBe listOf(0L, 20_000L, 53_000L)
        }

        test("the preview stops at a neighbour exactly where the commit will") {
            val pushed = lane().grabbed(502f, markers()).dragged(dxPx = -400f, pulledDp = 0f)

            val previewed = pushed.preview(set, BOOK_MS)[2].startTime
            withClue("clamped just past the chapter before, never across it") {
                (previewed > 20_000L) shouldBe true
            }
            pushed.committedStartMs(set, BOOK_MS) shouldBe previewed
        }

        // Spec 7.6/7.8: at the limit a boundary "resists with a subtle haptic rather than crossing a
        // neighbour". The phones buzz on this; it has to know when the drag is being held back.
        test("a drag held back by a neighbour says so; a free drag does not") {
            lane().grabbed(502f, markers()).dragged(dxPx = 30f, pulledDp = 0f).isHeldByNeighbour(set, BOOK_MS) shouldBe
                false
            lane().grabbed(502f, markers()).dragged(dxPx = -400f, pulledDp = 0f).isHeldByNeighbour(set, BOOK_MS) shouldBe
                true
            lane().isHeldByNeighbour(set, BOOK_MS) shouldBe false
        }

        // 2026-09-25: Android called retime on every pointer move, and each was its own undo frame —
        // so Undo after a drag walked it back one pixel at a time. One drag is one edit.
        test("a drag commits once, on release, at the previewed start") {
            val dragging =
                lane()
                    .grabbed(502f, markers())
                    .dragged(dxPx = 10f, pulledDp = 0f)
                    .dragged(dxPx = 10f, pulledDp = 0f)
                    .dragged(dxPx = 10f, pulledDp = 0f)

            dragging.committedStartMs(set, BOOK_MS) shouldBe 53_000L
            dragging.released().drag.shouldBeNull()
        }

        test("nothing is committed when nothing is being dragged") {
            lane().committedStartMs(set, BOOK_MS).shouldBeNull()
        }

        test("pulling away mid-drag slows it, as the shared fine-scrub rule says") {
            val fine = lane().grabbed(502f, markers()).dragged(dxPx = 10f, pulledDp = 300f)

            fine.committedStartMs(set, BOOK_MS) shouldBe 50_040L
        }

        test("the readout names the step and the exact time the boundary would take") {
            lane().readout(set, BOOK_MS).shouldBeNull()

            val fine = lane().grabbed(502f, markers()).dragged(dxPx = 10f, pulledDp = 300f)

            fine.readout(set, BOOK_MS) shouldBe "25× · 0:00:50.04"
        }

        test("zooming narrows the window around the point under the pointer, down to ten seconds") {
            val zoomed = lane().zoomed(factor = 0.5f, focusPx = 500f, bookDurationMs = BOOK_MS)

            zoomed.geometry.windowLengthMs shouldBe 50_000L
            withClue("the instant under the pointer stays under it") {
                zoomed.geometry.msOf(500f) shouldBe 50_000L
            }
            lane().zoomed(factor = 0.0001f, focusPx = 500f, bookDurationMs = BOOK_MS).geometry.windowLengthMs shouldBe
                10_000L
        }

        // The zoom buttons: no pointer to zoom around, and possibly no measured width yet either.
        test("zooming around the centre keeps the middle of the window in the middle, measured or not") {
            val unmeasured =
                TimelineLane(TimelineGeometry(windowStartMs = 100_000L, windowEndMs = 200_000L, widthPx = 0f))

            val zoomed = unmeasured.zoomedAroundCentre(factor = 0.5f, bookDurationMs = BOOK_MS)

            zoomed.geometry.windowStartMs shouldBe 125_000L
            zoomed.geometry.windowEndMs shouldBe 175_000L
            unmeasured.zoomedAroundCentre(factor = 100f, bookDurationMs = BOOK_MS).geometry.windowEndMs shouldBe BOOK_MS
        }

        test("the editor opens on the listener's position, with the window kept inside the book") {
            val hour = 3_600_000L
            val opened = TimelineLane.opening(bookDurationMs = hour, aroundMs = 1_800_000L, widthPx = 1_000f)

            opened.geometry.windowStartMs shouldBe 1_800_000L - DEFAULT_LANE_WINDOW_MS / 2
            TimelineLane.opening(hour, aroundMs = 3_590_000L, widthPx = 1_000f).geometry.windowEndMs shouldBe hour
            TimelineLane.opening(hour, aroundMs = null, widthPx = 1_000f).geometry.windowStartMs shouldBe 0L
        }

        test("a short book is shown whole rather than stretched past its end") {
            TimelineLane
                .opening(bookDurationMs = 90_000L, aroundMs = null, widthPx = 1_000f)
                .geometry
                .windowEndMs shouldBe 90_000L
        }

        test("centring moves the window to a point in the book without resizing it") {
            val centred = lane().centredOn(ms = 300_000L, bookDurationMs = BOOK_MS)

            centred.geometry.windowStartMs shouldBe 250_000L
            centred.geometry.windowLengthMs shouldBe 100_000L
        }

        test("measuring the lane keeps the window and takes the real width") {
            val measured = lane().measured(widthPx = 400f)

            measured.geometry.widthPx shouldBe 400f
            measured.geometry.windowLengthMs shouldBe 100_000L
            measured.grabbed(xPx = 201f, markers = markers()).drag.shouldNotBeNull()
        }
    })
