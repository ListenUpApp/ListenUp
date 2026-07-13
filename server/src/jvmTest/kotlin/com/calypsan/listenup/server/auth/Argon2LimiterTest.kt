package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the [Argon2Limiter] gate caps concurrent Argon2 work at its permit count (C3): the (N+1)th
 * caller blocks until an in-flight hash finishes. Uses counting stand-ins for the hash/verify
 * operations so the ceiling is observable without a real 64 MB Argon2 run.
 */
class Argon2LimiterTest :
    FunSpec({

        test("at most `permits` hashes run concurrently; the overflow caller blocks") {
            runBlocking(Dispatchers.Default) {
                val concurrent = AtomicInteger(0)
                val peak = AtomicInteger(0)
                val entered = Channel<Unit>(Channel.UNLIMITED)
                val release = CompletableDeferred<Unit>()

                val limiter =
                    Argon2Limiter(
                        permits = 2,
                        hashFn = {
                            val now = concurrent.incrementAndGet()
                            peak.updateAndGet { max -> if (now > max) now else max }
                            entered.send(Unit)
                            release.await() // hold the permit inside the critical section
                            concurrent.decrementAndGet()
                            "hash"
                        },
                        verifyFn = { _, _ -> true },
                    )

                val jobs = (1..5).map { launch { limiter.hash("password123") } }

                // Exactly `permits` callers enter the critical section...
                entered.receive()
                entered.receive()
                // ...and a third cannot slip in while both permits are held.
                withTimeoutOrNull(300) { entered.receive() } shouldBe null
                peak.get() shouldBe 2

                release.complete(Unit)
                jobs.joinAll()
                peak.get() shouldBe 2
            }
        }
    })
