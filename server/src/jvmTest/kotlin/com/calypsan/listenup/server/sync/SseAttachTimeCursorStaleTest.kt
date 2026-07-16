package com.calypsan.listenup.server.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.sse.sse as serverSse
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import org.sqlite.SQLiteConfig

/**
 * SERVER-SYNC-02: the SSE `Last-Event-ID` staleness check races the [ChangeBus] subscription
 * attach. [streamFirehose] snapshots [ChangeBus.oldestRetainedRevision] once, before the
 * `collectFirehoseEvents` coroutine is even launched; a burst landing between that snapshot and
 * the moment the [ChangeBus] subscription actually attaches can evict past the client's
 * `lastEventId` with no live signal — a silent gap instead of `CursorStale`.
 *
 * The real timing window is not reliably reproducible through `testApplication`'s dispatcher, so
 * this test drives the exact mechanism deterministically instead: the burst runs BEFORE the route
 * is even hit, so by construction the replay floor has already moved past `lastEventId` the
 * moment [collectFirehoseEvents] subscribes — exactly the state a genuine race would leave behind.
 * `collectFirehoseEvents`'s `onSubscription` re-check is what must catch it.
 */
class SseAttachTimeCursorStaleTest :
    FunSpec({

        test("a floor that already moved past lastEventId at attach time yields CursorStale, not a silent gap") {
            testApplication {
                val tmp = Files.createTempFile("listenup-attach-race-test-", ".db").toFile().apply { deleteOnExit() }
                val path = tmp.absolutePath
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$path"))
                val driver =
                    JdbcSqliteDriver(
                        "jdbc:sqlite:$path",
                        SQLiteConfig()
                            .apply {
                                enforceForeignKeys(true)
                                busyTimeout = 5000
                                setJournalMode(SQLiteConfig.JournalMode.WAL)
                            }.toProperties(),
                    )
                val sqlDb = ListenUpDatabase(driver)
                val bus = ChangeBus()
                val repo = TagRepository(db = sqlDb, bus = bus, registry = SyncRegistry())

                // Publish past the 256-deep replay buffer so DROP_OLDEST evicts revision 1 —
                // BEFORE the test route (and therefore collectFirehoseEvents) is ever hit. The
                // floor is already stale relative to lastEventId=1 the instant the subscription
                // attaches; nothing races here, it is pre-arranged.
                repeat(300) { i -> repo.upsert(Tag("tag-$i", "n$i", "n$i", 0, 0)) }

                application {
                    install(ServerSSE)
                    routing {
                        serverSse("/test/attach-race") {
                            collectFirehoseEvents(
                                bus = bus,
                                bookAccessPolicy = { error("book domain not exercised by this test") },
                                userId = "u1",
                                role = UserRole.MEMBER,
                                lastEventId = 1L,
                            )
                        }
                    }
                }

                try {
                    val client = createClient { install(SSE) }
                    client.sse("/test/attach-race") {
                        val event = incoming.first()
                        event.event shouldBe "control"
                        event.data!! shouldContain """"type":"SyncControl.CursorStale""""
                    }
                } finally {
                    driver.close()
                }
            }
        }
    })
