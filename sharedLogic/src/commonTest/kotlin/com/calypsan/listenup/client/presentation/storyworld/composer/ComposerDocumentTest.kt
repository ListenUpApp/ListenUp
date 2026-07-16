package com.calypsan.listenup.client.presentation.storyworld.composer

import com.calypsan.listenup.api.core.MentionTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ComposerDocumentTest :
    FunSpec({

        test("typing into an empty document creates a single Text segment") {
            val doc = ComposerDocument.empty().applyDisplayChange(newDisplay = "H", newCursor = 1)

            doc.segments shouldBe listOf(Segment.Text("H"))
            doc.displayText() shouldBe "H"
            doc.cursor shouldBe 1
        }

        test("typing after a mention leaves it intact") {
            val doc = ComposerDocument(segments = listOf(Segment.Mention("e1", "Ned")), cursor = 3)

            val edited = doc.applyDisplayChange(newDisplay = "NedX", newCursor = 4)

            edited.segments shouldBe listOf(Segment.Mention("e1", "Ned"), Segment.Text("X"))
            edited.displayText() shouldBe "NedX"
        }

        test("typing before a mention leaves it intact") {
            val doc = ComposerDocument(segments = listOf(Segment.Mention("e1", "Ned")), cursor = 0)

            val edited = doc.applyDisplayChange(newDisplay = "XNed", newCursor = 1)

            edited.segments shouldBe listOf(Segment.Text("X"), Segment.Mention("e1", "Ned"))
            edited.displayText() shouldBe "XNed"
        }

        test("deleting one char inside a mention span dissolves the whole mention") {
            val doc =
                ComposerDocument(
                    segments = listOf(Segment.Text("A"), Segment.Mention("e1", "Ned"), Segment.Text("B")),
                    cursor = 5,
                )

            // Display was "ANedB"; deleting the middle 'e' yields "ANdB".
            val edited = doc.applyDisplayChange(newDisplay = "ANdB", newCursor = 2)

            edited.segments shouldBe listOf(Segment.Text("ANdB"))
            edited.displayText() shouldBe "ANdB"
        }

        test("selecting across a mention and replacing it dissolves the mention") {
            val doc =
                ComposerDocument(
                    segments = listOf(Segment.Text("A"), Segment.Mention("e1", "Ned"), Segment.Text("B")),
                    cursor = 5,
                )

            // Display was "ANedB"; selecting "Ned" (the whole mention) and typing "XY" yields "AXYB".
            val edited = doc.applyDisplayChange(newDisplay = "AXYB", newCursor = 3)

            edited.segments shouldBe listOf(Segment.Text("AXYB"))
            edited.displayText() shouldBe "AXYB"
        }

        test("backspace at a mention's right edge dissolves the whole mention") {
            val doc =
                ComposerDocument(
                    segments = listOf(Segment.Text("A"), Segment.Mention("e1", "Bob"), Segment.Text("C")),
                    cursor = 5,
                )

            // Display was "ABobC"; backspacing the 'b' right before the cursor (which sat at the
            // mention's right edge) yields "ABoC". The edit window only touches the mention's last
            // character, but the whole mention still dissolves — it's atomic, not partially editable.
            val edited = doc.applyDisplayChange(newDisplay = "ABoC", newCursor = 3)

            edited.segments shouldBe listOf(Segment.Text("ABoC"))
            edited.displayText() shouldBe "ABoC"
        }

        test("insertMention with an active @ trigger replaces the trigger and its query") {
            val doc = ComposerDocument(segments = listOf(Segment.Text("Hello @quer")), cursor = 11)

            doc.activeTrigger() shouldBe Trigger(TriggerKind.MENTION, "quer", 6)

            val edited = doc.insertMention("e42", "Robert Baratheon")

            edited.segments shouldBe listOf(Segment.Text("Hello "), Segment.Mention("e42", "Robert Baratheon"))
            edited.displayText() shouldBe "Hello Robert Baratheon"
        }

        test("insertMention with an active [ trigger swallows the leading bracket") {
            val doc = ComposerDocument(segments = listOf(Segment.Text("[Ned")), cursor = 4)

            doc.activeTrigger() shouldBe Trigger(TriggerKind.MENTION, "Ned", 0)

            val edited = doc.insertMention("e1", "Ned Stark")

            edited.segments shouldBe listOf(Segment.Mention("e1", "Ned Stark"))
        }

        test("cursor lands right after an inserted mention, even with no active trigger") {
            val doc = ComposerDocument(segments = listOf(Segment.Text("Hello ")), cursor = 6)

            doc.activeTrigger() shouldBe null

            val edited = doc.insertMention("e9", "Sam")

            edited.segments shouldBe listOf(Segment.Text("Hello "), Segment.Mention("e9", "Sam"))
            edited.cursor shouldBe 9
        }

        test("activeTrigger detects @, [ and * with multi-word queries") {
            ComposerDocument(segments = listOf(Segment.Text("@King's Landing")), cursor = 15)
                .activeTrigger() shouldBe Trigger(TriggerKind.MENTION, "King's Landing", 0)

            ComposerDocument(segments = listOf(Segment.Text("[King's Landing")), cursor = 15)
                .activeTrigger() shouldBe Trigger(TriggerKind.MENTION, "King's Landing", 0)

            ComposerDocument(segments = listOf(Segment.Text("*moves to")), cursor = 9)
                .activeTrigger() shouldBe Trigger(TriggerKind.VERB, "moves to", 0)
        }

        test("activeTrigger is inactive when the trigger char is preceded by non-whitespace") {
            val doc = ComposerDocument(segments = listOf(Segment.Text("foo@bar")), cursor = 7)

            doc.activeTrigger() shouldBe null
        }

        test("activeTrigger is inactive when a newline sits between the trigger and the cursor") {
            val doc = ComposerDocument(segments = listOf(Segment.Text("@foo\nbar")), cursor = 8)

            doc.activeTrigger() shouldBe null
        }

        test("activeTrigger is inactive when the cursor sits before the trigger character") {
            val doc = ComposerDocument(segments = listOf(Segment.Text("@bob is here")), cursor = 0)

            doc.activeTrigger() shouldBe null
        }

        test("displayText/rawText round-trip through fromRaw for a name containing | and ]]") {
            // MentionTokens.token sanitizes '|' -> '¦' and ']]' -> '] ]' at write time; the name
            // below deliberately contains both so the sanitized token survives its own closing
            // "]]" without an early match (the "]]" doesn't sit at the very end of the name).
            val cachedName = "King|Landing]] Rules"
            val raw = "See ${MentionTokens.token("e7", cachedName)} now"

            val doc = ComposerDocument.fromRaw(raw) { null }

            doc.displayText() shouldBe "See King¦Landing] ] Rules now"
            doc.rawText() shouldBe raw
        }

        test("malformed mention-token-like text stays literal") {
            val raw = "Hello [[e:no-pipe-here world"

            val doc = ComposerDocument.fromRaw(raw) { null }

            doc.segments shouldBe listOf(Segment.Text(raw))
            doc.displayText() shouldBe raw
            doc.rawText() shouldBe raw
            doc.cursor shouldBe raw.length
        }

        test("mentionSpans reports correct offsets for multiple mentions") {
            val doc =
                ComposerDocument(
                    segments =
                        listOf(
                            Segment.Text("Hi "),
                            Segment.Mention("e1", "Ned"),
                            Segment.Text(" and "),
                            Segment.Mention("e2", "Robb"),
                        ),
                    cursor = 15,
                )

            doc.mentionSpans() shouldBe listOf(3 until 6, 11 until 15)
        }
    })
