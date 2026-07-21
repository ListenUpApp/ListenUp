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
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ActivityRecorder
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
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.koin.ktor.ext.inject

/**
 * Firehose ACL proof for the `collection_books` domain, post-SERVER-SYNC-04: [isCollectionEventHidden]
 * must gate a live Created/Updated event by the payload's `collectionId`, never by parsing the
 * event's wire `id` — which is now an opaque per-row value (SERVER-SYNC-04), not the
 * `"$collectionId:$bookId"` composite the old `CollectionBookId.fromString` parse assumed.
 *
 * Before the fix, a Created event for an opaque-id junction row would either crash
 * `isCollectionEventHidden` (the parsed "id" has no `:`) or, worse, silently misroute access —
 * either way the gate could not be trusted. Mirrors [ActivityFirehoseAccessTest]'s shape: the
 * owner adds a book to a private collection FIRST, then each viewer opens the RPC firehose
 * ([rpcFirehose], principal mapped to the same `(userId, role)` their JWT carried on the old SSE
 * surface); the bus's replay buffer delivers the events deterministically and a trailing sentinel
 * bounds each collector.
 */
class CollectionBooksFirehoseAccessTest :
    FunSpec({

        val sentinelMarker = "SENTINEL-cb-firehose"

        suspend fun HttpClient.setupRootId(): String =
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("owner@cb-firehose.example", "x".repeat(8), "Owner"))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data.user.id.value

        suspend fun HttpClient.registerMemberId(): String =
            post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("member@cb-firehose.example", "y".repeat(8), "Member"))
            }.body<AppResult<RegisterResult>>()
                .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                .data
                .let { it as RegisterResult.Authenticated }
                .session.user.id.value

        test(
            "firehose withholds a private collection_books Created event (opaque id) from a non-member, delivers to the owner",
        ).config(timeout = 2.minutes) {
            val libraryRoot = Files.createTempDirectory("listenup-cb-firehose-acl-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val ownerId = restClient.setupRootId()
                    val memberId = restClient.registerMemberId()

                    seedTestLibraryAndFolder()
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestBook("private-book")

                    val collections by application.inject<CollectionRepository>()
                    val collectionBooks by application.inject<CollectionBookRepository>()
                    collections.upsert(
                        CollectionSyncPayload(
                            id = "owner-private",
                            libraryId = "test-library",
                            ownerId = ownerId,
                            name = "owner-private",
                            isInbox = false,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val recorder by application.inject<ActivityRecorder>()
                    val bus by application.inject<ChangeBus>()
                    val policy by application.inject<BookAccessPolicy>()

                    // The owner adds the book FIRST — a fresh, server-minted opaque id
                    // (SERVER-SYNC-04), never a "$collectionId:$bookId" composite — exercising the
                    // exact shape isCollectionEventHidden must now handle. The sentinel is a
                    // non-book activity (ungated, per ActivityFirehoseAccessTest) — it bounds both
                    // collectors without depending on a collection the member could see.
                    collectionBooks.upsert(
                        CollectionBookSyncPayload(
                            id = Uuid.random().toString(),
                            collectionId = "owner-private",
                            bookId = "private-book",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )
                    recorder.record(
                        ownerId,
                        ActivityType.SHELF_CREATED,
                        shelfId = "sentinel",
                        shelfName = sentinelMarker,
                    )

                    val memberEvents = mutableListOf<SyncFrame>()
                    rpcFirehose(bus, memberPrincipal(memberId), bookAccessPolicy = { policy })
                        .domainFrames()
                        .filter { it.domain == "collection_books" || it.domain == "activities" }
                        .onEach { memberEvents += it }
                        .first { it.json.contains(sentinelMarker) }

                    val ownerEvents = mutableListOf<SyncFrame>()
                    rpcFirehose(bus, rootPrincipal(ownerId), bookAccessPolicy = { policy })
                        .domainFrames()
                        .filter { it.domain == "collection_books" || it.domain == "activities" }
                        .onEach { ownerEvents += it }
                        .first { it.json.contains(sentinelMarker) }

                    // Member: never sees the private junction row, but does see the public sentinel.
                    memberEvents.none { it.json.contains("private-book") } shouldBe true
                    memberEvents.any { it.json.contains(sentinelMarker) } shouldBe true

                    // Owner: sees both — the private junction Created event and the sentinel.
                    ownerEvents.any { it.json.contains("private-book") } shouldBe true
                    ownerEvents.any { it.json.contains(sentinelMarker) } shouldBe true
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
