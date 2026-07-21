package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.SystemCollectionType
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Firehose ACL proof for the `activities` domain: the live RPC firehose ([SyncStreamServiceImpl])
 * runs every event through [isActivityEventHidden] (via the shared [firehoseGateReason] chain)
 * before delivery, so a member sees an activity iff its book is accessible — while a
 * `book_id == null` row is public to everyone, and ROOT/ADMIN bypass the probe entirely.
 *
 * Where [ActivityCatchUpAccessTest] pins the catch-up/digest fragment and [com.calypsan.listenup.server.api.ActivityAclE2ETest]
 * pins the feed RPC, this pins the THIRD surface — the live firehose gate — through `module()`'s
 * real repositories and access policy, with each viewer's [rpcFirehose] principal mapped to the
 * same `(userId, role)` their JWT carried on the old SSE surface, so the three surfaces cannot
 * disagree.
 *
 * Setup mirrors [com.calypsan.listenup.server.api.ActivityAclE2ETest]: A (ROOT, via `/auth/setup`)
 * records the activities; B (MEMBER, via `/auth/register` under OPEN policy) is the constrained
 * viewer holding the default ALL_BOOKS grant. `public-book` joins ALL_BOOKS (reachable by B under
 * pure union); `private-book` is gated into A's own collection (invisible to B).
 *
 * A records four activities in revision order FIRST; each viewer then opens the RPC firehose —
 * the bus's replay buffer delivers the recorded events in publish order, so collection is
 * deterministic. A trailing non-book "sentinel" activity bounds each collector: everything a
 * viewer received up to and including the sentinel is exactly what the gate let through.
 *
 * NOTE: activities declare [com.calypsan.listenup.client.data.sync.domains]-side `DeleteSemantics.CatchUpOnly`
 * — the server never emits an activity tombstone over the live tail — so the gate's "tombstones pass
 * ungated" branch is unreachable via the firehose and is covered structurally at the catch-up seam
 * instead. This test exercises the three live-reachable decisions: withhold-private, deliver-non-book,
 * bypass-ROOT.
 */
class ActivityFirehoseAccessTest :
    FunSpec({

        val sentinelMarker = "SENTINEL-firehose"

        /** Runs first-user setup; returns A's ROOT user id. */
        suspend fun HttpClient.setupRootId(): String =
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("alice@firehose-acl.example", "x".repeat(8), "Alice"))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data.user.id.value

        /** Registers a second user (MEMBER under OPEN policy); returns B's user id. */
        suspend fun HttpClient.registerMemberId(): String =
            post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("bob@firehose-acl.example", "y".repeat(8), "Bob"))
            }.body<AppResult<RegisterResult>>()
                .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                .data
                .let { it as RegisterResult.Authenticated }
                .session.user.id.value

        test("firehose withholds the private-book activity from a member, delivers non-book to all, bypasses ROOT") {
            val libraryRoot = Files.createTempDirectory("listenup-activity-firehose-acl-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val aliceId = restClient.setupRootId()
                    val bobId = restClient.registerMemberId()

                    // ── Seed library + two books: one public (joins ALL_BOOKS), one gated private ──
                    seedTestLibraryAndFolder()
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestBook("public-book")
                    sql.seedTestBook("private-book")

                    val collections by application.inject<CollectionRepository>()
                    val collectionBooks by application.inject<CollectionBookRepository>()
                    // Gate private-book into A's own collection — invisible to B.
                    collections.upsert(
                        CollectionSyncPayload(
                            id = "alice-private",
                            libraryId = "test-library",
                            ownerId = aliceId,
                            name = "alice-private",
                            isInbox = false,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    collectionBooks.upsert(
                        CollectionBookSyncPayload(
                            id = "alice-private:private-book",
                            collectionId = "alice-private",
                            bookId = "private-book",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )
                    // Make public-book public: place it in the bootstrap library's ALL_BOOKS system
                    // collection, which B's default grant targets under pure union.
                    val collectionService by application.inject<CollectionServiceImpl>()
                    val registry by application.inject<LibraryRegistry>()
                    val allBooksId =
                        (
                            collectionService.getOrCreateSystemCollection(
                                registry.currentLibrary().value,
                                SystemCollectionType.ALL_BOOKS,
                            ) as AppResult.Success
                        ).data.id.value
                    collectionBooks.upsert(
                        CollectionBookSyncPayload(
                            id = "$allBooksId:public-book",
                            collectionId = allBooksId,
                            bookId = "public-book",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )

                    val recorder by application.inject<ActivityRecorder>()
                    val bus by application.inject<ChangeBus>()
                    val policy by application.inject<BookAccessPolicy>()

                    // Record FIRST, in revision order: public, private, non-book, sentinel. The
                    // bus's replay buffer then serves each subscriber deterministically.
                    recorder.record(aliceId, ActivityType.FINISHED_BOOK, bookId = "public-book")
                    recorder.record(aliceId, ActivityType.FINISHED_BOOK, bookId = "private-book")
                    recorder.record(aliceId, ActivityType.USER_JOINED)
                    recorder.record(
                        aliceId,
                        ActivityType.SHELF_CREATED,
                        shelfId = "sentinel",
                        shelfName = sentinelMarker,
                    )

                    val memberEvents = mutableListOf<SyncFrame>()
                    rpcFirehose(bus, memberPrincipal(bobId), bookAccessPolicy = { policy })
                        .domainFrames()
                        .filter { it.domain == "activities" }
                        .onEach { memberEvents += it }
                        .first { it.json.contains(sentinelMarker) }

                    val rootEvents = mutableListOf<SyncFrame>()
                    rpcFirehose(bus, rootPrincipal(aliceId), bookAccessPolicy = { policy })
                        .domainFrames()
                        .filter { it.domain == "activities" }
                        .onEach { rootEvents += it }
                        .first { it.json.contains(sentinelMarker) }

                    // Member: accessible book present, private withheld, non-book delivered.
                    memberEvents.any { it.json.contains(""""bookId":"public-book"""") } shouldBe true
                    memberEvents.none { it.json.contains("private-book") } shouldBe true
                    memberEvents.any { it.json.contains(""""type":"user_joined"""") } shouldBe true

                    // Root: bypasses the gate — sees every activity, including the private-book one.
                    rootEvents.any { it.json.contains(""""bookId":"public-book"""") } shouldBe true
                    rootEvents.any { it.json.contains(""""bookId":"private-book"""") } shouldBe true
                    rootEvents.any { it.json.contains(""""type":"user_joined"""") } shouldBe true
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
