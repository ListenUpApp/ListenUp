package com.calypsan.listenup.client.presentation.storyworld.composer

import com.calypsan.listenup.api.sync.WorldEventType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AssertionParserTest :
    FunSpec({

        test("enters between two mentions binds the object") {
            val segments =
                listOf(Segment.Mention("ned", "Ned"), Segment.Text(" enters "), Segment.Mention("wf", "Winterfell"))

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.ENTERS_SCENE, "ned", "wf")
        }

        test("moves to, arrives at, and travels to all bind MOVES_TO with a required object") {
            val movesTo =
                listOf(Segment.Mention("ned", "Ned"), Segment.Text(" moves to "), Segment.Mention("wf", "Winterfell"))
            val arrivesAt =
                listOf(Segment.Mention("ned", "Ned"), Segment.Text(" arrives at "), Segment.Mention("wf", "Winterfell"))
            val travelsTo =
                listOf(Segment.Mention("ned", "Ned"), Segment.Text(" travels to "), Segment.Mention("wf", "Winterfell"))

            AssertionParser.parse(movesTo) shouldBe Assertion(WorldEventType.MOVES_TO, "ned", "wf")
            AssertionParser.parse(arrivesAt) shouldBe Assertion(WorldEventType.MOVES_TO, "ned", "wf")
            AssertionParser.parse(travelsTo) shouldBe Assertion(WorldEventType.MOVES_TO, "ned", "wf")
        }

        test("departs between two mentions binds the object") {
            val segments =
                listOf(Segment.Mention("ned", "Ned"), Segment.Text(" departs "), Segment.Mention("wf", "Winterfell"))

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.DEPARTS, "ned", "wf")
        }

        test("leaves followed by a mention binds the object as DEPARTS") {
            val segments =
                listOf(Segment.Mention("ned", "Ned"), Segment.Text(" leaves "), Segment.Mention("wf", "Winterfell"))

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.DEPARTS, "ned", "wf")
        }

        test("leaves with no following mention is EXITS_SCENE with no object") {
            val segments = listOf(Segment.Mention("ned", "Ned"), Segment.Text(" leaves"))

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.EXITS_SCENE, "ned", null)
        }

        test("moves to without a following mention does not parse") {
            val segments = listOf(Segment.Mention("ned", "Ned"), Segment.Text(" moves to"))

            AssertionParser.parse(segments) shouldBe null
        }

        test("a verb with trailing prose after it binds no object") {
            val segments = listOf(Segment.Mention("ned", "Ned"), Segment.Text(" departs for the south"))

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.DEPARTS, "ned", null)
        }

        test("text that isn't exactly a verb between two mentions doesn't bind the object") {
            // "leaves for" != "leaves" (trimmed), so the two-mention form never fires even though
            // a mention follows it. Falling back to the subject-only scan, "leaves for [...]"'s
            // text segment trimStart()'s to "leaves for" — which starts with "leaves" followed by
            // whitespace-then-more-prose — so it resolves to EXITS_SCENE with no object, entirely
            // ignoring the mention that happens to follow. This is deliberately pinned: it is the
            // documented behavior, not a bug.
            val segments =
                listOf(
                    Segment.Mention("ned", "Ned"),
                    Segment.Text(" leaves for "),
                    Segment.Mention("south", "The South"),
                )

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.EXITS_SCENE, "ned", null)
        }

        test("verb matching is case-insensitive") {
            val segments =
                listOf(Segment.Mention("ned", "Ned"), Segment.Text(" Enters "), Segment.Mention("wf", "Winterfell"))

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.ENTERS_SCENE, "ned", "wf")
        }

        test("the first matching pattern wins when two are present") {
            val segments =
                listOf(
                    Segment.Mention("ned", "Ned"),
                    Segment.Text(" enters "),
                    Segment.Mention("wf", "Winterfell"),
                    Segment.Text(". "),
                    Segment.Mention("robb", "Robb"),
                    Segment.Text(" leaves"),
                )

            AssertionParser.parse(segments) shouldBe Assertion(WorldEventType.ENTERS_SCENE, "ned", "wf")
        }

        test("no mentions at all yields null") {
            val segments = listOf(Segment.Text("Winter is coming to the north."))

            AssertionParser.parse(segments) shouldBe null
        }

        test("a mention at the end with no following text yields null") {
            val segments = listOf(Segment.Text("Some prose "), Segment.Mention("ned", "Ned"))

            AssertionParser.parse(segments) shouldBe null
        }

        test("prose-only content yields null") {
            val segments = listOf(Segment.Text("The weather was fine."))

            AssertionParser.parse(segments) shouldBe null
        }
    })
