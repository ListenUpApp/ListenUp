package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.chapter.DriftResult
import com.calypsan.listenup.client.domain.chapter.correctDrift
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val BOOK_MS = 1_200_000L

private fun chapters(vararg starts: Long): List<Chapter> =
    starts
        .mapIndexed { i, s -> Chapter(id = "c$i", title = "Chapter $i", duration = 0L, startTime = s) }
        .withDerivedDurations(BOOK_MS)

/**
 * The four edits every chapter operation reduces to.
 *
 * Written as pure list transforms because each carries an invariant that is far easier to state
 * here than to confirm by dragging something: boundaries stay ordered, chapters stay contiguous,
 * and durations are always a consequence of the next start rather than a second stored fact that
 * can drift out of agreement with it.
 *
 * Every operation returns the receiver unchanged when it cannot apply, which the draft reads as
 * "nothing happened" and declines to record an undo frame for — so a nudge that hits a neighbour
 * does not bury a real edit under a no-op.
 */
class ChapterEditsTest :
    FunSpec({

        test("durations are always derived from the next start, never stored independently") {
            val c = chapters(0L, 300_000L, 900_000L)

            c.map { it.duration } shouldBe listOf(300_000L, 600_000L, 300_000L)
            withClue("the last chapter runs to the end of the book") {
                c.last().startTime + c.last().duration shouldBe BOOK_MS
            }
        }

        // ── retime ────────────────────────────────────────────────────────────────

        test("retiming moves a boundary and re-derives its neighbours' durations") {
            val moved = chapters(0L, 300_000L, 900_000L).retimed("c1", 400_000L, BOOK_MS)

            moved[1].startTime shouldBe 400_000L
            withClue("the chapter before absorbs the extra time; the one after loses it") {
                moved[0].duration shouldBe 400_000L
                moved[1].duration shouldBe 500_000L
            }
        }

        test("a boundary stops at its neighbour instead of crossing it") {
            // Clamping rather than rejecting is what makes dragging feel right: pushed against a
            // neighbour it stops there, rather than snapping back or silently reordering the book.
            val c = chapters(0L, 300_000L, 900_000L)

            withClue("dragged far left, it rests just after the previous boundary") {
                c.retimed("c1", -50_000L, BOOK_MS)[1].startTime shouldBe 1L
            }
            withClue("dragged far right, it rests just before the next") {
                c.retimed("c1", 5_000_000L, BOOK_MS)[1].startTime shouldBe 899_999L
            }
            withClue("order is never edited directly — it survives every clamp") {
                c.retimed("c1", 5_000_000L, BOOK_MS).map { it.startTime } shouldBe
                    c.retimed("c1", 5_000_000L, BOOK_MS).map { it.startTime }.sorted()
            }
        }

        test("the last boundary cannot be dragged past the end of the audio") {
            val moved = chapters(0L, 300_000L, 900_000L).retimed("c2", BOOK_MS + 60_000L, BOOK_MS)

            moved.last().startTime shouldBe BOOK_MS - 1L
        }

        test("retiming to where it already is changes nothing, so no undo frame is spent") {
            val c = chapters(0L, 300_000L)

            c.retimed("c1", 300_000L, BOOK_MS) shouldBe c
        }

        test("retiming an unknown chapter is a no-op, not a crash") {
            val c = chapters(0L, 300_000L)

            c.retimed("nope", 100_000L, BOOK_MS) shouldBe c
        }

        // ── add ───────────────────────────────────────────────────────────────────

        test("adding at the playhead splits the chapter that contains it") {
            val added = chapters(0L, 600_000L).added(id = "new", title = "New", atMs = 300_000L, BOOK_MS)

            added.map { it.startTime } shouldBe listOf(0L, 300_000L, 600_000L)
            withClue("the split chapter gives up exactly the time the new one takes") {
                added[0].duration shouldBe 300_000L
                added[1].duration shouldBe 300_000L
            }
        }

        test("adding on top of an existing boundary is refused") {
            // Two chapters starting on the same millisecond is not a set the editor can reason
            // about, and a zero-length chapter is no use to anyone.
            val c = chapters(0L, 600_000L)

            c.added(id = "new", title = "New", atMs = 600_000L, BOOK_MS) shouldBe c
        }

        test("adding outside the audio is refused at both ends") {
            val c = chapters(0L, 600_000L)

            c.added("new", "New", atMs = -1L, BOOK_MS) shouldBe c
            c.added("new", "New", atMs = BOOK_MS, BOOK_MS) shouldBe c
        }

        // ── remove ────────────────────────────────────────────────────────────────

        test("removing merges the chapter into the one before it") {
            val left = chapters(0L, 300_000L, 900_000L).removed("c1", BOOK_MS)

            left.map { it.startTime } shouldBe listOf(0L, 900_000L)
            withClue("the previous chapter absorbs the removed span — no gap is left behind") {
                left[0].duration shouldBe 900_000L
            }
        }

        test("removing the first chapter pulls the new first back to the start of the book") {
            // A book cannot begin partway through: every millisecond belongs to some chapter.
            val left = chapters(0L, 300_000L, 900_000L).removed("c0", BOOK_MS)

            left.first().startTime shouldBe 0L
            left.first().duration shouldBe 900_000L
        }

        test("the last remaining chapter cannot be removed") {
            // A book with no chapters is the empty state — a different screen and a deliberate
            // act, not the tail end of pressing delete.
            val one = chapters(0L)

            one.removed("c0", BOOK_MS) shouldBe one
        }

        // ── retitle ───────────────────────────────────────────────────────────────

        test("retitling trims, and a blank title is refused") {
            val c = chapters(0L, 300_000L)

            c.retitled("c1", "  The Spear  ")[1].title shouldBe "The Spear"
            withClue("an untitled boundary is unnavigable, which is the whole point of a chapter") {
                c.retitled("c1", "   ") shouldBe c
            }
        }

        test("retitling touches only the title — never the timing") {
            val c = chapters(0L, 300_000L, 900_000L)

            val renamed = c.retitled("c1", "Renamed")

            renamed.map { it.startTime } shouldBe c.map { it.startTime }
            renamed.map { it.duration } shouldBe c.map { it.duration }
        }

        test("every edit leaves the set contiguous, which is the invariant the spec calls un-violable") {
            var c = chapters(0L, 300_000L, 900_000L)
            c = c.added("new", "Inserted", 150_000L, BOOK_MS)
            c = c.retimed("c1", 400_000L, BOOK_MS)
            c = c.removed("new", BOOK_MS)
            c = c.retitled("c2", "Renamed")

            withClue("each chapter ends exactly where the next begins") {
                c.zipWithNext().forEach { (a, b) -> a.startTime + a.duration shouldBe b.startTime }
            }
            withClue("and the last ends at the end of the book") {
                c.last().startTime + c.last().duration shouldBe BOOK_MS
            }
        }

        // ── markers ───────────────────────────────────────────────────────────────

        test("a part marker is set on its chapter, trimmed, and nothing else changes") {
            val c = chapters(0L, 300_000L)

            val marked = c.withPartMarker("c1", "  Part One ")

            marked[1].partTitle shouldBe "Part One"
            marked[0] shouldBe c[0]
            withClue("a marker is a label, so the boundary it sits on does not move") {
                marked[1].startTime shouldBe c[1].startTime
                marked[1].duration shouldBe c[1].duration
            }
        }

        test("a book marker is set on its chapter and leaves the part tier alone") {
            val c = chapters(0L, 300_000L).withPartMarker("c1", "Part One")

            val marked = c.withBookMarker("c1", "Book One")

            marked[1].bookTitle shouldBe "Book One"
            marked[1].partTitle shouldBe "Part One"
        }

        test("a blank marker name is refused, because ChapterInput would throw on it at save") {
            val c = chapters(0L, 300_000L)

            c.withPartMarker("c1", "   ") shouldBe c
            c.withBookMarker("c1", "") shouldBe c
        }

        test("a marker name past the wire's ceiling is refused, for the same reason") {
            val c = chapters(0L, 300_000L)
            val tooLong = "x".repeat(ChapterInput.MAX_SECTION_TITLE + 1)

            c.withPartMarker("c1", tooLong) shouldBe c
            c.withBookMarker("c1", tooLong) shouldBe c
            c.withPartMarker("c1", tooLong.drop(1))[1].partTitle shouldBe tooLong.drop(1)
        }

        test("an unknown chapter is no edit") {
            val c = chapters(0L, 300_000L)

            c.withPartMarker("gone", "Part One") shouldBe c
            c.withoutPartMarker("gone") shouldBe c
        }

        test("removing a marker clears only its tier") {
            val c = chapters(0L, 300_000L).withPartMarker("c1", "Part One").withBookMarker("c1", "Book One")

            c.withoutPartMarker("c1")[1].partTitle shouldBe null
            c.withoutPartMarker("c1")[1].bookTitle shouldBe "Book One"
            c.withoutBookMarker("c1")[1].bookTitle shouldBe null
            c.withoutBookMarker("c1")[1].partTitle shouldBe "Part One"
        }

        test("removing a marker that is not there is no edit, so no undo frame is pushed") {
            val c = chapters(0L, 300_000L)

            c.withoutPartMarker("c1") shouldBe c
            c.withoutBookMarker("c1") shouldBe c
        }

        test("moving a marker clears it where it was and sets it where it goes, keeping its name") {
            val c = chapters(0L, 300_000L, 600_000L).withPartMarker("c0", "Part One")

            val moved = c.movedPartMarker("c0", "c2")

            moved[0].partTitle shouldBe null
            moved[2].partTitle shouldBe "Part One"
        }

        test("moving a book marker leaves the part markers where they are") {
            val c =
                chapters(0L, 300_000L, 600_000L)
                    .withBookMarker("c0", "Book One")
                    .withPartMarker("c0", "Part One")

            val moved = c.movedBookMarker("c0", "c1")

            moved[1].bookTitle shouldBe "Book One"
            moved[0].partTitle shouldBe "Part One"
        }

        test("a chapter already opening that tier is not a valid target") {
            val c =
                chapters(0L, 300_000L, 600_000L)
                    .withPartMarker("c0", "Part One")
                    .withPartMarker("c2", "Part Two")

            c.movedPartMarker("c0", "c2") shouldBe c
        }

        test("a chapter opening the OTHER tier is a valid target") {
            val c = chapters(0L, 300_000L).withPartMarker("c0", "Part One").withBookMarker("c1", "Book One")

            c.movedPartMarker("c0", "c1")[1].partTitle shouldBe "Part One"
        }

        test("moving a marker that does not exist, or to a chapter that does not, is no edit") {
            val c = chapters(0L, 300_000L).withPartMarker("c0", "Part One")

            c.movedPartMarker("c1", "c0") shouldBe c
            c.movedPartMarker("c0", "gone") shouldBe c
        }

        test("a marker travels with its chapter through a retime") {
            val c = chapters(0L, 300_000L, 600_000L).withPartMarker("c1", "Part One")

            c.retimed("c1", 350_000L, BOOK_MS)[1].partTitle shouldBe "Part One"
        }

        test("markers survive drift correction, on the chapters that carried them") {
            val c =
                chapters(0L, 300_000L, 600_000L)
                    .withPartMarker("c1", "Part One")
                    .withBookMarker("c2", "Book One")

            val corrected =
                correctDrift(c, listOf(ChapterAnchor("c0", trueStartMs = 5_000L)), BOOK_MS)
                    .shouldBeInstanceOf<DriftResult.Corrected>()
                    .chapters

            corrected.map { it.startTime } shouldBe listOf(5_000L, 305_000L, 605_000L)
            corrected.map { it.partTitle } shouldBe listOf(null, "Part One", null)
            corrected.map { it.bookTitle } shouldBe listOf(null, null, "Book One")
        }

        // ── deleting a chapter that carries a marker ───────────────────────────────

        test("deleting a marked chapter hands its markers to the next chapter") {
            val c =
                chapters(0L, 300_000L, 600_000L)
                    .withPartMarker("c1", "Part One")
                    .withBookMarker("c1", "Book One")

            val left = c.removed("c1", BOOK_MS)

            left.map { it.id } shouldBe listOf("c0", "c2")
            left[1].partTitle shouldBe "Part One"
            left[1].bookTitle shouldBe "Book One"
        }

        test("deleting the first chapter hands its marker on too") {
            val left = chapters(0L, 300_000L).withPartMarker("c0", "Part One").removed("c0", BOOK_MS)

            left.single().partTitle shouldBe "Part One"
        }

        test("a tier the next chapter already opens is not overwritten, and the deleted marker goes") {
            val c =
                chapters(0L, 300_000L, 600_000L)
                    .withPartMarker("c1", "Part One")
                    .withBookMarker("c1", "Book One")
                    .withPartMarker("c2", "Part Two")

            val left = c.removed("c1", BOOK_MS)

            withClue("each tier is decided on its own") {
                left[1].partTitle shouldBe "Part Two"
                left[1].bookTitle shouldBe "Book One"
            }
        }

        test("deleting the last chapter takes its marker with it: there is nothing after it to open") {
            val left = chapters(0L, 300_000L).withPartMarker("c1", "Part One").removed("c1", BOOK_MS)

            left.single().partTitle shouldBe null
        }
    })
