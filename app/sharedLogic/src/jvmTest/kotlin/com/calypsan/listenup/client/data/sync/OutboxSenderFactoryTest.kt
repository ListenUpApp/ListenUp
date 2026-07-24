package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class OutboxSenderFactoryTest :
    FunSpec({
        val noop = PendingOperationSender { AppResult.Success(Unit) }

        test("a complete binding map constructs") {
            outboxSender(OutboxChannels.all.associateWith { noop }) shouldNotBe null
        }

        test("a missing channel fails loudly, naming it") {
            val dropped = OutboxChannels.all.first()
            val incomplete = OutboxChannels.all.drop(1).associateWith { noop }
            shouldThrow<IllegalArgumentException> { outboxSender(incomplete) }
                .message shouldContain dropped.name
        }
    })
