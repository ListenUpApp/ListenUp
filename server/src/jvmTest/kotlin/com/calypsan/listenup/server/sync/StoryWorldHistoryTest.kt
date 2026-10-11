package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

/**
 * [StoryWorldOp.UNKNOWN] exists only so an older app can decode an operation a newer server added.
 * The server must never record it: a history row whose operation nobody can read cannot be reverted.
 */
class StoryWorldHistoryTest :
    FunSpec({
        fun StoryWorldHistory.recordFor(op: StoryWorldOp) =
            record(
                entityId = "e1",
                op = op,
                actor = UserId("u1"),
                occurredAt = 1,
                revision = 1,
                before = null,
                after = entityPayload("e1", homeBookId = "b1"),
            )

        test("recording an UNKNOWN operation is refused and writes nothing") {
            withSqlDatabase {
                val history = StoryWorldHistory(sql)

                shouldThrow<IllegalArgumentException> { history.recordFor(StoryWorldOp.UNKNOWN) }

                history.listFor(EntityId("e1")).shouldBeEmpty()
            }
        }

        test("a real operation is still recorded") {
            withSqlDatabase {
                val history = StoryWorldHistory(sql)

                history.recordFor(StoryWorldOp.CREATE)

                history.listFor(EntityId("e1")) shouldHaveSize 1
            }
        }
    })
