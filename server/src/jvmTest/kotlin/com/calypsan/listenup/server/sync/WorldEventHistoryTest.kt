package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

/**
 * The event twin of [StoryWorldHistoryTest]: [StoryWorldOp.UNKNOWN] exists only so an older app can
 * decode an operation a newer server added, and the server must never record it.
 */
class WorldEventHistoryTest :
    FunSpec({
        fun WorldEventHistory.recordFor(op: StoryWorldOp) =
            record(
                eventId = "w1",
                op = op,
                actor = UserId("u1"),
                occurredAt = 1,
                revision = 1,
                before = null,
                after = null,
            )

        test("recording an UNKNOWN operation is refused and writes nothing") {
            withSqlDatabase {
                val history = WorldEventHistory(sql)

                shouldThrow<IllegalArgumentException> { history.recordFor(StoryWorldOp.UNKNOWN) }

                history.listFor(WorldEventId("w1")).shouldBeEmpty()
            }
        }

        test("a real operation is still recorded") {
            withSqlDatabase {
                val history = WorldEventHistory(sql)

                history.recordFor(StoryWorldOp.CREATE)

                history.listFor(WorldEventId("w1")) shouldHaveSize 1
            }
        }
    })
