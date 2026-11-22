package com.calypsan.listenup.client.data.discovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The Android 11–13 resolver handles one resolve at a time and fails an overlapping one, so two
 * servers found back to back used to leave the second unresolved until discovery restarted.
 */
class LegacyResolveQueueTest :
    FunSpec({
        val serverA = "server-a"
        val serverB = "server-b"
        val internalError = 0

        fun queueRecording(started: MutableList<String>) = LegacyResolveQueue<String> { started += it }

        test("two services found back to back: the second is resolved once the first completes") {
            val started = mutableListOf<String>()
            val queue = queueRecording(started)

            queue.enqueue(serverA)
            queue.enqueue(serverB)
            started shouldBe listOf(serverA)

            queue.onResolved(serverA)
            started shouldBe listOf(serverA, serverB)
        }

        test("a failed resolve still lets the next one start") {
            val started = mutableListOf<String>()
            val queue = queueRecording(started)

            queue.enqueue(serverA)
            queue.enqueue(serverB)
            queue.onFailed(serverA, internalError)

            started shouldBe listOf(serverA, serverB)
        }

        test("an already-active failure is retried once, then given up") {
            val started = mutableListOf<String>()
            val queue = queueRecording(started)

            queue.enqueue(serverA)
            queue.onFailed(serverA, LegacyResolveQueue.FAILURE_ALREADY_ACTIVE)
            started shouldBe listOf(serverA, serverA)

            queue.onFailed(serverA, LegacyResolveQueue.FAILURE_ALREADY_ACTIVE)
            queue.enqueue(serverB)
            started shouldBe listOf(serverA, serverA, serverB)
        }

        test("stopping discovery clears the queue, and a late outcome of the old resolve is ignored") {
            val started = mutableListOf<String>()
            val queue = queueRecording(started)

            queue.enqueue(serverA)
            queue.enqueue(serverB)
            queue.clear()
            queue.onResolved(serverA)
            started shouldBe listOf(serverA)

            queue.enqueue(serverB)
            started shouldBe listOf(serverA, serverB)
        }
    })
