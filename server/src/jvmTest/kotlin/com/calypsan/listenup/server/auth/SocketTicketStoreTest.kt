@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Advanceable clock so expiry is driven deterministically, not by wall time. */
private class TicketClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/**
 * Pins the three properties that make a URL-borne ticket safer than a URL-borne JWT: it is
 * single-use, it expires in seconds, and it is the only thing the query parameter will ever accept.
 * Lose any one of them and the ticket is just a token with extra steps.
 */
class SocketTicketStoreTest :
    FunSpec({

        test("a freshly issued ticket redeems to the access token it stands for") {
            runTest {
                val store = SocketTicketStore(TicketClock(Clock.System.now()))

                val ticket = store.issue("the-access-token")

                store.redeem(ticket) shouldBe "the-access-token"
            }
        }

        test("a ticket redeems exactly once") {
            runTest {
                val store = SocketTicketStore(TicketClock(Clock.System.now()))
                val ticket = store.issue("the-access-token")

                store.redeem(ticket) shouldBe "the-access-token"

                // The whole point: a ticket copied out of a proxy log is already spent.
                store.redeem(ticket).shouldBeNull()
            }
        }

        test("a ticket expires once its ttl elapses") {
            runTest {
                val clock = TicketClock(Clock.System.now())
                val store = SocketTicketStore(clock, ttl = 30.seconds)
                val ticket = store.issue("the-access-token")

                clock.now += 31.seconds

                store.redeem(ticket).shouldBeNull()
            }
        }

        test("a ticket still redeems just inside its ttl") {
            runTest {
                val clock = TicketClock(Clock.System.now())
                val store = SocketTicketStore(clock, ttl = 30.seconds)
                val ticket = store.issue("the-access-token")

                clock.now += 29.seconds

                store.redeem(ticket) shouldBe "the-access-token"
            }
        }

        test("an unknown ticket redeems to nothing") {
            runTest {
                val store = SocketTicketStore(TicketClock(Clock.System.now()))

                store.redeem("never-issued").shouldBeNull()
            }
        }

        test("each issue mints a distinct ticket") {
            runTest {
                val store = SocketTicketStore(TicketClock(Clock.System.now()))

                store.issue("the-access-token") shouldNotBe store.issue("the-access-token")
            }
        }

        test("a ticket is url-safe, so it survives a query string and the token68 alphabet") {
            runTest {
                val store = SocketTicketStore(TicketClock(Clock.System.now()))

                // base64url without padding sits inside RFC 7235's token68 alphabet, so
                // HttpAuthHeader.Single can never throw on a ticket we minted and no ticket ever
                // needs URL-escaping on the way out.
                repeat(20) {
                    store.issue("the-access-token").matches(Regex("^[A-Za-z0-9_-]+$")) shouldBe true
                }
            }
        }

        test("expired tickets do not accumulate") {
            runTest {
                val clock = TicketClock(Clock.System.now())
                val store = SocketTicketStore(clock, ttl = 30.seconds)
                repeat(50) { store.issue("the-access-token") }

                clock.now += 31.seconds
                // Any later operation sweeps what the clock has already invalidated.
                store.issue("kept")

                store.liveTicketCount() shouldBe 1
            }
        }
    })
