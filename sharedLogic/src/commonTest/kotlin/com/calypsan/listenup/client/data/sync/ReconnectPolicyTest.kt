package com.calypsan.listenup.client.data.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

/** Pins the shared reconnect-backoff ladder consumed by the firehose and pre-auth watch loops. */
class ReconnectPolicyTest :
    FunSpec({

        test("reconnectDelay schedule: 1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s") {
            val schedule = (0..7).map { reconnectDelayMillis(attempt = it) }
            schedule shouldContainExactly
                listOf(
                    1_000L,
                    2_000L,
                    4_000L,
                    8_000L,
                    16_000L,
                    32_000L,
                    60_000L,
                    60_000L,
                )
        }
    })
