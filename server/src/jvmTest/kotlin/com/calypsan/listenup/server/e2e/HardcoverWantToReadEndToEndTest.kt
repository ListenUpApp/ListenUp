package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.hardcover.FakeHardcoverLibrary
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverPullStore
import com.calypsan.listenup.server.hardcover.HardcoverShelfEntryState
import com.calypsan.listenup.server.hardcover.HardcoverShelfEntryStore
import com.calypsan.listenup.server.hardcover.HardcoverStatus
import com.calypsan.listenup.server.hardcover.HardcoverTokens
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import org.koin.ktor.ext.get as koinGet

private const val KEPT_HC_BOOK = 427_578L
private const val READ_HC_BOOK = 1_000L

/**
 * #1539 end to end, with only Hardcover faked (over a real socket). Registration makes the starter
 * "To Read" shelf. Two books on the user's Want to Read list land on it, visible over the shelf RPC
 * and the `shelf_books` sync domain. One is then read on Hardcover, so it leaves. The user takes the
 * other off by hand over the shelf RPC, and it stays off although it is still on Want to Read.
 */
class HardcoverWantToReadEndToEndTest :
    FunSpec({

        test("Want to Read lands on To Read and syncs; a read book leaves; a book taken off by hand stays off") {
            val hardcover = FakeHardcoverLibrary()
            hardcover.addEdition(
                FakeHardcoverLibrary.Edition(
                    id = 9_001L,
                    bookId = KEPT_HC_BOOK,
                    title = "Project Hail Mary",
                    authors = listOf("Andy Weir"),
                    asin = "B08G9RZBTT",
                    readingFormatId = 2,
                    defaultAudioEditionId = 9_001L,
                ),
            )
            hardcover.addEdition(
                FakeHardcoverLibrary.Edition(
                    id = 1_001L,
                    bookId = READ_HC_BOOK,
                    title = "11/22/63",
                    authors = listOf("Stephen King"),
                    asin = "B005UR3VFO",
                    readingFormatId = 2,
                    defaultAudioEditionId = 1_001L,
                ),
            )
            hardcover.seedShelf(KEPT_HC_BOOK, HardcoverStatus.WANT_TO_READ, editionId = 9_001L)
            hardcover.seedShelf(READ_HC_BOOK, HardcoverStatus.WANT_TO_READ, editionId = 1_001L)

            val fakeHardcover =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    routing {
                        post("/v1/graphql") {
                            val reply = hardcover.handle(call.receiveText())
                            reply.headers.forEach { (name, value) -> call.response.header(name, value) }
                            call.respondText(reply.body, ContentType.Application.Json, reply.status)
                        }
                    }
                }.start(wait = false)
            try {
                val port = runBlocking { fakeHardcover.engine.resolvedConnectors() }.first().port
                testApplication {
                    useIsolatedTestConfig(
                        rescanOnStartup = false,
                        hardcoverClientId = "listenup-test-client",
                        hardcoverApiBaseUrl = "http://127.0.0.1:$port",
                    )
                    lateinit var app: Application
                    application {
                        module()
                        app = this
                    }
                    startApplication()

                    val session = publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    val token = session.accessToken.value
                    val userId = session.user.id.value
                    val sql = app.koinGet<ListenUpDatabase>()
                    sql.seedTestLibraryAndFolder()
                    sql.seedTestBook("book-kept", asin = "B08G9RZBTT")
                    sql.seedTestBook("book-read", asin = "B005UR3VFO")
                    app.koinGet<HardcoverConnectionStore>().save(
                        userId,
                        HardcoverMe(42, "simon"),
                        HardcoverTokens("hc_at_e2e", "hc_rt_e2e", 604_800, HARDCOVER_SCOPES),
                    )

                    val shelves = authedService<ShelfService>(token)
                    val toRead = shelves.listMyShelves().shouldSucceed().single { it.name == "To Read" }
                    val pulls = app.koinGet<HardcoverPullStore>()
                    val hardcoverService = authedService<HardcoverService>(token)

                    suspend fun onToRead(): Set<String> =
                        shelves
                            .getShelf(toRead.id)
                            .shouldSucceed()
                            .books
                            .map { it.bookId }
                            .toSet()

                    suspend fun syncedOnToRead(): Set<String> =
                        authedService<SyncStreamService>(token)
                            .pullDomain("shelf_books", since = 0, limit = 500)
                            .shouldSucceed()
                            .rows(ShelfBookSyncPayload.serializer())
                            .filter { it.deletedAt == null && it.shelfId == toRead.id.value }
                            .map { it.bookId }
                            .toSet()

                    // Sync now, then wait for a full pull whose page began no earlier than this moment.
                    suspend fun fullPullFromNow() {
                        val from = System.currentTimeMillis()
                        hardcoverService.syncNow().shouldSucceed()
                        eventually(20.seconds) { (pulls.pullState(userId)!!.lastFullPullAt ?: 0L) shouldBeGreaterThanOrEqualTo from }
                    }

                    fullPullFromNow()
                    onToRead() shouldBe setOf("book-kept", "book-read")
                    syncedOnToRead() shouldBe setOf("book-kept", "book-read")

                    hardcover.moveTo(READ_HC_BOOK, HardcoverStatus.READ)
                    shelves.removeBookFromShelf(toRead.id, BookId("book-kept")).shouldSucceed()
                    fullPullFromNow()

                    onToRead() shouldBe emptySet()
                    syncedOnToRead() shouldBe emptySet()
                    val records = app.koinGet<HardcoverShelfEntryStore>()
                    records.recordFor(userId, "book-kept")!!.state shouldBe HardcoverShelfEntryState.USER_REMOVED
                    records.recordFor(userId, "book-read") shouldBe null
                }
            } finally {
                fakeHardcover.stop(0, 0)
            }
        }
    })
