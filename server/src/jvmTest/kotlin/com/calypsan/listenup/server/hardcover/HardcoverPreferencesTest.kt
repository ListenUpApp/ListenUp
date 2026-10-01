package com.calypsan.listenup.server.hardcover

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private const val USER = "u1"
private const val OTHER_USER = "u2"
private const val T0 = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC

private class PreferencesRig(
    val sql: ListenUpDatabase,
) {
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val activity = HardcoverSyncActivity()
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock, activity)
    val outbox = HardcoverOutbox(sql, clock)
    val preferences = HardcoverPreferences(sql, clock, activity)

    init {
        sql.seedTestUser(USER)
        sql.seedTestUser(OTHER_USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook("book-1")
        sql.seedTestBook("book-2")
    }

    suspend fun connect(hcUserId: Long = 42L): HardcoverConnection.Connected =
        connections.save(USER, HardcoverMe(hcUserId, "reader"), HardcoverTokens("at", "rt", 604_800, HARDCOVER_SCOPES))
}

private fun preferencesTest(block: suspend PreferencesRig.() -> Unit) = withSqlDatabase { runTest { PreferencesRig(sql).block() } }

/** Spec #1538's storage and switching: the choice, its default, what a switch drops, and what it never touches. */
class HardcoverPreferencesTest :
    FunSpec({

        test("with no choice recorded, a listener shares as they listen") {
            preferencesTest {
                sql.hardcoverShareMode(USER) shouldBe HardcoverShareMode.AS_I_LISTEN
                connect().shareMode shouldBe HardcoverShareMode.AS_I_LISTEN
                connections.connectionState(USER).shouldBeInstanceOf<HardcoverConnection.Connected>().shareMode shouldBe
                    HardcoverShareMode.AS_I_LISTEN
            }
        }

        test("a recorded choice is the caller's alone, and Connected carries it") {
            preferencesTest {
                connect()
                preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)

                sql.hardcoverShareMode(USER) shouldBe HardcoverShareMode.FINISHED_ONLY
                sql.hardcoverShareMode(OTHER_USER) shouldBe HardcoverShareMode.AS_I_LISTEN
                connections.connectionState(USER).shouldBeInstanceOf<HardcoverConnection.Connected>().shareMode shouldBe
                    HardcoverShareMode.FINISHED_ONLY
            }
        }

        test("switching to Only when I finish drops queued starts and progress, keeps every finish, and touches no one else") {
            preferencesTest {
                connect()
                outbox.enqueueStart(USER, "book-1", listenThrough = T0, startedAt = T0, isReread = false)
                outbox.enqueueProgress(USER, "book-1", listenThrough = T0, positionSeconds = 90L, notBefore = T0)
                outbox.enqueueFinish(USER, "book-2", listenThrough = T0, finishedAt = T0 + 100_000L)
                outbox.enqueueStart(OTHER_USER, "book-1", listenThrough = T0, startedAt = T0, isReread = false)

                preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)

                outbox.pendingFor(USER).map { it.bookId to it.payload } shouldBe
                    listOf("book-2" to HardcoverPushPayload.Finish(T0 + 100_000L))
                outbox.pendingFor(OTHER_USER).map { it.payload } shouldBe
                    listOf(HardcoverPushPayload.Start(startedAt = T0, isReread = false))
            }
        }

        test("switching back to As I listen only records the choice") {
            preferencesTest {
                connect()
                preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)
                outbox.enqueueFinish(USER, "book-2", listenThrough = T0, finishedAt = T0 + 100_000L)

                preferences.setShareMode(USER, HardcoverShareMode.AS_I_LISTEN)

                sql.hardcoverShareMode(USER) shouldBe HardcoverShareMode.AS_I_LISTEN
                outbox.pendingFor(USER).map { it.payload } shouldBe listOf(HardcoverPushPayload.Finish(T0 + 100_000L))
            }
        }

        test("the choice survives a disconnect, a reconnect, and a reconnect to another Hardcover account") {
            preferencesTest {
                connect()
                preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)

                connections.delete(USER)
                sql.hardcoverShareMode(USER) shouldBe HardcoverShareMode.FINISHED_ONLY

                connect().shareMode shouldBe HardcoverShareMode.FINISHED_ONLY
                connect(hcUserId = 99L).shareMode shouldBe HardcoverShareMode.FINISHED_ONLY
                connections.connectionState(USER).shouldBeInstanceOf<HardcoverConnection.Connected>().shareMode shouldBe
                    HardcoverShareMode.FINISHED_ONLY
            }
        }

        test("a choice is announced, so a watching client's Connected is republished with it") {
            preferencesTest {
                activity.changes.test {
                    preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)
                    awaitItem() shouldBe USER
                }
            }
        }

        test("a stored mode this build doesn't know reads as Only when I finish: it shares less, never more") {
            preferencesTest {
                sql.hardcoverPreferencesQueries.upsertShareMode(user_id = USER, share_mode = "AFTER_AN_HOUR", updated_at = T0)
                sql.hardcoverShareMode(USER) shouldBe HardcoverShareMode.FINISHED_ONLY
            }
        }
    })
