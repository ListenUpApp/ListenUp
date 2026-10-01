package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val USER = "u1"
private const val T0 = 1_779_451_200_000L

private class LivenessRig(
    dbs: SqlTestDatabases,
) {
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val activity = HardcoverSyncActivity()
    val store =
        HardcoverConnectionStore(dbs.sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock, activity)
    private val oauth = HardcoverOAuthClient(HttpClient(), "id", "https://hc.test")
    val linker =
        HardcoverLinker(oauth, FakeHardcoverLibrary().client(), store, CoroutineScope(Dispatchers.Unconfined), clock, activity)

    init {
        dbs.sql.seedTestUser(USER)
    }

    suspend fun connect() = store.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))

    /** Waits (in real time, since republishing hops to the database's dispatcher) for the stream to say [what]. */
    suspend fun awaitState(what: (HardcoverConnection) -> Boolean): HardcoverConnection =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { linker.observe(USER).first(what) } }
}

private fun livenessTest(block: suspend LivenessRig.() -> Unit) = withSqlDatabase { runTest { LivenessRig(this@withSqlDatabase).block() } }

/** Spec B5's "last sync" and its errors reach a watching client without it resubscribing. */
class HardcoverConnectionLivenessTest :
    FunSpec({

        test("a fresh connection has never synced and has no problem") {
            livenessTest {
                connect()
                linker.observe(USER).value shouldBe HardcoverConnection.Connected("reader", T0)
            }
        }

        test("a push landing republishes Connected with its time") {
            livenessTest {
                connect()
                linker.observe(USER)
                store.markSynced(USER, T0 + 5_000)
                awaitState { (it as? HardcoverConnection.Connected)?.lastSyncedAt == T0 + 5_000 }
            }
        }

        test("a push stalled past its cap shows as PUSH_STALLED until a push lands") {
            livenessTest {
                connect()
                linker.observe(USER)
                store.recordPushError(USER, "HTTP 500: boom")
                awaitState { (it as? HardcoverConnection.Connected)?.syncProblem == HardcoverSyncProblem.PUSH_STALLED }
                store.markSynced(USER, T0 + 1)
                awaitState { (it as? HardcoverConnection.Connected)?.syncProblem == null }
            }
        }

        test("a stalled pull shows as PULL_STALLED, and a stalled push outranks it") {
            livenessTest {
                connect()
                linker.observe(USER)
                store.recordPullError(USER, "HTTP 503")
                awaitState { (it as? HardcoverConnection.Connected)?.syncProblem == HardcoverSyncProblem.PULL_STALLED }
                store.recordPushError(USER, "HTTP 500")
                awaitState { (it as? HardcoverConnection.Connected)?.syncProblem == HardcoverSyncProblem.PUSH_STALLED }
            }
        }

        test("Sync now shows while it runs, and its failure outranks a stored problem") {
            livenessTest {
                connect()
                store.recordPushError(USER, "HTTP 500")
                linker.observe(USER)
                activity.syncRequested(USER)
                awaitState { (it as? HardcoverConnection.Connected)?.isSyncing == true }
                activity.pullFailed(USER, served = activity.generation(USER))
                awaitState {
                    it is HardcoverConnection.Connected && !it.isSyncing &&
                        it.syncProblem == HardcoverSyncProblem.SYNC_NOW_FAILED
                }
            }
        }

        test("a state seeded after a sync began already says so") {
            livenessTest {
                connect()
                store.markSynced(USER, T0 + 9)
                activity.syncRequested(USER)
                val seeded = linker.observe(USER).value.shouldBeInstanceOf<HardcoverConnection.Connected>()
                seeded.lastSyncedAt shouldBe T0 + 9
                seeded.isSyncing shouldBe true
            }
        }

        test("a republish never turns a broken connection back into a connected one") {
            livenessTest {
                connect()
                linker.observe(USER)
                linker.breakIfConnected(USER, HardcoverBrokenReason.REVOKED) shouldBe true
                activity.healthChanged(USER)
                awaitState { it is HardcoverConnection.Broken }
                linker.observe(USER).value.shouldBeInstanceOf<HardcoverConnection.Broken>()
            }
        }
    })
