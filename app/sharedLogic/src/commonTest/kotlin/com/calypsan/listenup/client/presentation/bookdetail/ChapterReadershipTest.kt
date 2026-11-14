package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.readers.Reader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Pins [chapterReadership]: who sits on which chapter row, part header and "All N chapters" button, in what order,
 * with how many faces, and the clauses a screen reader hears.
 */
class ChapterReadershipTest :
    FunSpec({
        // Four 1000 ms chapters: c1 0–1000, c2 1000–2000, c3 2000–3000, c4 3000–4000. The book is 4000 ms.
        val chapters =
            listOf(
                Chapter(id = "c1", title = "One", duration = 1_000L, startTime = 0L),
                Chapter(id = "c2", title = "Two", duration = 1_000L, startTime = 1_000L),
                Chapter(id = "c3", title = "Three", duration = 1_000L, startTime = 2_000L),
                Chapter(id = "c4", title = "Four", duration = 1_000L, startTime = 3_000L),
            )

        fun reader(
            userId: String,
            positionMs: Long? = null,
            pct: Int? = positionMs?.let { (it * 100 / 4_000L).toInt() },
            lastPlayedAtMs: Long? = null,
            isYou: Boolean = false,
            finishes: List<Long> = emptyList(),
        ) = Reader(
            userId = userId,
            displayName = "$userId Surname",
            isYou = isYou,
            currentProgressPct = pct,
            finishes = finishes,
            currentPositionMs = positionMs,
            lastPlayedAtMs = lastPlayedAtMs,
        )

        fun ChapterReaders?.ids(): List<String> = this?.run { readers.map { it.userId } }.orEmpty()

        test("a reader sits on the chapter their exact position is in") {
            val readership = chapterReadership(listOf(reader("maya", positionMs = 2_500L)), chapters, emptySet())

            readership.onChapter("c3").ids() shouldBe listOf("maya")
            readership.onChapter("c2").shouldBeNull()
        }

        test("a reader exactly on a boundary belongs to the chapter that starts there") {
            val readership = chapterReadership(listOf(reader("maya", positionMs = 1_000L)), chapters, emptySet())

            readership.onChapter("c2").ids() shouldBe listOf("maya")
            readership.onChapter("c1").shouldBeNull()
        }

        test("without an exact position, the percent of the book's length places the reader") {
            // 60% of 4000 ms is 2400 ms: chapter three.
            val readership = chapterReadership(listOf(reader("tom", positionMs = null, pct = 60)), chapters, emptySet())

            readership.onChapter("c3").ids() shouldBe listOf("tom")
        }

        test("you, anyone finished, and anyone at 0% are on no chapter") {
            val readership =
                chapterReadership(
                    listOf(
                        reader("me", positionMs = 500L, isYou = true),
                        reader("done", positionMs = null, pct = null, finishes = listOf(9L)),
                        reader("zero", positionMs = null, pct = 0),
                    ),
                    chapters,
                    emptySet(),
                )

            chapters.forEach { readership.onChapter(it.id).shouldBeNull() }
            readership.beyond(emptyList()).shouldBeNull()
        }

        test("a reader a few seconds in is on the first chapter even though their percent rounds to 0") {
            val readership =
                chapterReadership(listOf(reader("early", positionMs = 30L, pct = 0)), chapters, emptySet())

            readership.onChapter("c1").ids() shouldBe listOf("early")
        }

        test("up to three readers all show their faces") {
            val three = listOf("a", "b", "c").map { reader(it, positionMs = 100L) }
            val stack = chapterReadership(three, chapters, emptySet()).onChapter("c1").shouldNotBeNull()

            stack.faces.map { it.userId } shouldBe listOf("a", "b", "c")
            stack.overflowCount shouldBe 0
        }

        test("four or more readers show two faces and count the rest") {
            val five = listOf("a", "b", "c", "d", "e").map { reader(it, positionMs = 100L) }
            val stack = chapterReadership(five, chapters, emptySet()).onChapter("c1").shouldNotBeNull()

            stack.faces.map { it.userId } shouldBe listOf("a", "b")
            stack.overflowCount shouldBe 3
        }

        test("live readers come first, then the most recently active, then those with no known last play") {
            val readers =
                listOf(
                    reader("unknown", positionMs = 100L, lastPlayedAtMs = null),
                    reader("older", positionMs = 100L, lastPlayedAtMs = 10L),
                    reader("newer", positionMs = 100L, lastPlayedAtMs = 20L),
                    reader("live", positionMs = 100L, lastPlayedAtMs = 1L),
                )
            val stack = chapterReadership(readers, chapters, liveUserIds = setOf("live")).onChapter("c1")

            stack.ids() shouldBe listOf("live", "newer", "older", "unknown")
            stack.shouldNotBeNull().faces.map { it.isLive } shouldBe listOf(true, false)
        }

        test("a row's sentence names who is listening now apart from who is only on the chapter") {
            val readers =
                listOf(
                    reader("maya", positionMs = 2_100L),
                    reader("tom", positionMs = 2_200L),
                )
            val stack =
                chapterReadership(readers, chapters, liveUserIds = setOf("maya")).onChapter("c3").shouldNotBeNull()

            stack.place shouldBe ReadersPlace.ThisChapter
            stack.listeningNow.shouldNotBeNull().let { clause ->
                clause.named.map { it.firstName } shouldBe listOf("maya")
                clause.form shouldBe ReaderClauseForm.One
            }
            stack.notListening.shouldNotBeNull().let { clause ->
                clause.named.map { it.firstName } shouldBe listOf("tom")
                clause.form shouldBe ReaderClauseForm.One
            }
        }

        test("a clause names two people and counts the others") {
            fun clauseOf(count: Int): ReaderClause =
                chapterReadership((1..count).map { reader("r$it", positionMs = 100L) }, chapters, emptySet())
                    .onChapter("c1")
                    .shouldNotBeNull()
                    .notListening
                    .shouldNotBeNull()

            clauseOf(2).form shouldBe ReaderClauseForm.Two
            clauseOf(3).let { clause ->
                clause.form shouldBe ReaderClauseForm.TwoAndOneOther
                clause.named.map { ref -> ref.userId } shouldBe listOf("r1", "r2")
                clause.othersCount shouldBe 1
            }
            clauseOf(4).let { clause ->
                clause.form shouldBe ReaderClauseForm.TwoAndOthers
                clause.othersCount shouldBe 2
            }
        }

        test("nobody listening now leaves that clause out") {
            val stack =
                chapterReadership(listOf(reader("tom", positionMs = 100L)), chapters, emptySet())
                    .onChapter("c1")
                    .shouldNotBeNull()

            stack.listeningNow.shouldBeNull()
        }

        test("the All chapters button carries the readers outside the rows shown, saying they are further on") {
            val readers =
                listOf(
                    reader("shown", positionMs = 500L),
                    reader("maya", positionMs = 3_500L),
                    reader("tom", positionMs = 2_500L),
                )
            val beyond =
                chapterReadership(readers, chapters, liveUserIds = setOf("maya")).beyond(listOf("c1", "c2"))

            beyond.ids() shouldBe listOf("maya", "tom")
            beyond.shouldNotBeNull().place shouldBe ReadersPlace.FurtherOn
        }

        test("readers only before the rows shown are on earlier chapters") {
            val beyond =
                chapterReadership(listOf(reader("tom", positionMs = 500L)), chapters, emptySet())
                    .beyond(listOf("c3", "c4"))

            beyond.shouldNotBeNull().place shouldBe ReadersPlace.Earlier
        }

        test("readers on both sides of the rows shown are on other chapters") {
            val readers = listOf(reader("tom", positionMs = 500L), reader("maya", positionMs = 3_500L))
            val beyond = chapterReadership(readers, chapters, emptySet()).beyond(listOf("c2", "c3"))

            beyond.shouldNotBeNull().place shouldBe ReadersPlace.OtherChapters
        }

        test("the button carries nobody once every reader's row is shown") {
            val readership = chapterReadership(listOf(reader("tom", positionMs = 500L)), chapters, emptySet())

            readership.beyond(chapters.map { it.id }).shouldBeNull()
        }

        test("a collapsed part's header carries everyone in its chapters, the folded opener included") {
            val marked =
                listOf(
                    Chapter(id = "p1", title = "Part One", duration = 1_000L, startTime = 0L, partTitle = "Part One"),
                    Chapter(id = "c2", title = "Two", duration = 1_000L, startTime = 1_000L),
                    Chapter(id = "p2", title = "Part Two", duration = 1_000L, startTime = 2_000L, partTitle = "Part Two"),
                    Chapter(id = "c4", title = "Four", duration = 1_000L, startTime = 3_000L),
                )
            val readers =
                listOf(
                    reader("opener", positionMs = 500L),
                    reader("inside", positionMs = 1_500L),
                    reader("later", positionMs = 3_500L),
                )
            val readership = chapterReadership(readers, marked, emptySet())

            readership.inPart("p1").shouldNotBeNull().let { part ->
                part.readers.map { it.userId } shouldBe listOf("opener", "inside")
                part.place shouldBe ReadersPlace.ThisPart
            }
            readership.inPart("p2").ids() shouldBe listOf("later")
            readership.onChapter("p1").ids() shouldBe listOf("opener")
        }

        test("a part with nobody in it carries nothing") {
            val marked =
                listOf(
                    Chapter(id = "p1", title = "Part One", duration = 1_000L, startTime = 0L, partTitle = "Part One"),
                    Chapter(id = "c2", title = "Two", duration = 1_000L, startTime = 1_000L),
                )

            chapterReadership(emptyList(), marked, emptySet()).inPart("p1").shouldBeNull()
        }

        test("a book with no chapters places nobody") {
            val readership = chapterReadership(listOf(reader("tom", positionMs = 500L)), emptyList(), emptySet())

            readership shouldBe ChapterReadership.None
            readership.beyond(emptyList()).shouldBeNull()
        }
    })
