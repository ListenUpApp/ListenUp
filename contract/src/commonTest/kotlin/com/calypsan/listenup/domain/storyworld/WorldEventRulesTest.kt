package com.calypsan.listenup.domain.storyworld

import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.WorldEventId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun upsert(
    type: WorldEventType,
    text: String = "",
    detail: String? = null,
    subject: String? = null,
    obj: String? = null,
    bookId: String? = null,
    positionMs: Long? = null,
) = WorldEventUpsert(
    id = WorldEventId("w1"),
    type = type,
    text = text,
    detail = detail,
    homeSeriesId = SeriesId("s1"),
    bookId = bookId?.let(::BookId),
    positionMs = positionMs,
    subjectEntityId = subject?.let(::EntityId),
    objectEntityId = obj?.let(::EntityId),
)

private fun ValidationError?.field(): String? = this?.field

class WorldEventRulesTest :
    FunSpec({
        test("an event has exactly one home") {
            WorldEventRules.homeProblem(SeriesId("s1"), null).shouldBeNull()
            WorldEventRules.homeProblem(null, BookId("b1")).shouldBeNull()
            WorldEventRules.homeProblem(null, null).shouldBeInstanceOf<ValidationError>()
            WorldEventRules.homeProblem(SeriesId("s1"), BookId("b1")).shouldBeInstanceOf<ValidationError>()
        }

        test("UNKNOWN is refused on create only") {
            WorldEventRules.creationProblem(WorldEventType.UNKNOWN).field() shouldBe "type"
            WorldEventRules.creationProblem(WorldEventType.NOTE).shouldBeNull()
        }

        test("an anchor is a book and a moment together, and never before the start") {
            WorldEventRules.contentProblem(upsert(WorldEventType.NOTE, text = "x", bookId = "b1")).field() shouldBe "positionMs"
            WorldEventRules.contentProblem(upsert(WorldEventType.NOTE, text = "x", positionMs = 5)).field() shouldBe "positionMs"
            WorldEventRules
                .contentProblem(upsert(WorldEventType.NOTE, text = "x", bookId = "b1", positionMs = -1))
                .field() shouldBe "positionMs"
            WorldEventRules.contentProblem(upsert(WorldEventType.NOTE, text = "x", bookId = "b1", positionMs = 0)).shouldBeNull()
        }

        test("a NOTE needs text; typed events may be text-free") {
            WorldEventRules.contentProblem(upsert(WorldEventType.NOTE, text = "  ")).field() shouldBe "text"
            WorldEventRules
                .contentProblem(upsert(WorldEventType.BELONGS_TO, subject = "c1", obj = "p1"))
                .shouldBeNull()
        }

        test("text and detail have caps") {
            val long = "x".repeat(WorldEventRules.MAX_TEXT + 1)
            WorldEventRules.contentProblem(upsert(WorldEventType.NOTE, text = long)).field() shouldBe "text"
            WorldEventRules
                .contentProblem(upsert(WorldEventType.JOINS, detail = "x".repeat(WorldEventRules.MAX_DETAIL + 1), subject = "c", obj = "g"))
                .field() shouldBe "detail"
        }

        test("only JOINS and LEAVES take a detail") {
            WorldEventRules.contentProblem(upsert(WorldEventType.JOINS, detail = "Primus", subject = "c", obj = "g")).shouldBeNull()
            WorldEventRules.contentProblem(upsert(WorldEventType.LEAVES, detail = "expelled", subject = "c", obj = "g")).shouldBeNull()
            WorldEventRules
                .contentProblem(upsert(WorldEventType.BELONGS_TO, detail = "x", subject = "c", obj = "p"))
                .field() shouldBe "detail"
        }

        test("membership types need both sides; movement needs a subject; MOVES_TO needs a destination") {
            WorldEventRules.contentProblem(upsert(WorldEventType.JOINS, obj = "g")).field() shouldBe "subjectEntityId"
            WorldEventRules.contentProblem(upsert(WorldEventType.LEAVES, subject = "c")).field() shouldBe "objectEntityId"
            WorldEventRules.contentProblem(upsert(WorldEventType.BELONGS_TO, subject = "c")).field() shouldBe "objectEntityId"
            WorldEventRules.contentProblem(upsert(WorldEventType.ENTERS_SCENE)).field() shouldBe "subjectEntityId"
            WorldEventRules.contentProblem(upsert(WorldEventType.MOVES_TO, subject = "c")).field() shouldBe "objectEntityId"
            WorldEventRules.contentProblem(upsert(WorldEventType.DEPARTS, subject = "c")).shouldBeNull()
        }

        test("an edit carrying UNKNOWN is checked only for its shape; the type it keeps is checked by whoever knows it") {
            WorldEventRules.contentProblem(upsert(WorldEventType.UNKNOWN)).shouldBeNull()
            WorldEventRules.contentProblem(upsert(WorldEventType.UNKNOWN), type = WorldEventType.JOINS).field() shouldBe "subjectEntityId"
        }

        test("kinds: membership takes a character and a group or people; movement goes to a location") {
            WorldEventRules.kindProblem(WorldEventType.JOINS, EntityKind.CHARACTER, EntityKind.GROUP).shouldBeNull()
            WorldEventRules
                .kindProblem(WorldEventType.JOINS, EntityKind.CHARACTER, EntityKind.PEOPLE)
                .shouldBeInstanceOf<WorldEventError.WrongEntityKind>()
            WorldEventRules.kindProblem(WorldEventType.BELONGS_TO, EntityKind.CHARACTER, EntityKind.PEOPLE).shouldBeNull()
            WorldEventRules
                .kindProblem(WorldEventType.BELONGS_TO, EntityKind.LOCATION, EntityKind.PEOPLE)
                .shouldBeInstanceOf<WorldEventError.WrongEntityKind>()
            WorldEventRules.kindProblem(WorldEventType.MOVES_TO, EntityKind.ITEM, EntityKind.LOCATION).shouldBeNull()
            WorldEventRules
                .kindProblem(WorldEventType.MOVES_TO, EntityKind.CHARACTER, EntityKind.GROUP)
                .shouldBeInstanceOf<WorldEventError.WrongEntityKind>()
            WorldEventRules.kindProblem(WorldEventType.NOTE, EntityKind.CONCEPT, EntityKind.EVENT).shouldBeNull()
            WorldEventRules.kindProblem(WorldEventType.DIES, EntityKind.UNKNOWN, null).shouldBeNull()
        }
    })
