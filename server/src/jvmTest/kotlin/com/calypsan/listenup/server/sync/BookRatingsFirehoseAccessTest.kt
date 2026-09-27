package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
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
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.koin.ktor.ext.inject

/**
 * Firehose ACL proof for the `book_ratings` domain: the live RPC firehose
 * ([SyncStreamServiceImpl]) runs every event through [isBookJunctionEventHidden] (via the shared
 * [firehoseGateReason] chain), exactly like `book_tags`/`book_moods`, so a member sees a rating
 * iff its book is accessible — while ROOT/ADMIN bypass the probe entirely.
 *
 * Mirrors [ActivityFirehoseAccessTest]'s shape: the owner rates both books FIRST, then each
 * viewer opens the RPC firehose ([rpcFirehose], principal mapped to the same `(userId, role)`
 * their JWT carried on the old SSE surface); the bus's replay buffer delivers the recorded events
 * deterministically, and a trailing non-book "sentinel" activity bounds each collector.
 */
class BookRatingsFirehoseAccessTest :
    FunSpec({

        val sentinelMarker = "SENTINEL-ratings-firehose"

        suspend fun ApplicationTestBuilder.setupRootId(): String =
            publicAuthService()
                .setupRoot(RegisterRequest("alice@ratings-firehose.example", "x".repeat(8), "Alice"))
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data.user.id.value

        suspend fun ApplicationTestBuilder.registerMemberId(): String =
            publicAuthService()
                .register(RegisterRequest("bob@ratings-firehose.example", "y".repeat(8), "Bob"))
                .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                .data
                .let { it as RegisterResult.Authenticated }
                .session.user.id.value

        test("firehose withholds a private-book rating from a member, delivers the public one, bypasses ROOT") {
            val libraryRoot = Files.createTempDirectory("listenup-ratings-firehose-acl-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val aliceId = setupRootId()
                    val bobId = registerMemberId()

                    // ── Seed library + two books: one public (joins ALL_BOOKS), one gated private ──
                    seedTestLibraryAndFolder()
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestBook("public-book")
                    sql.seedTestBook("private-book")

                    val collections by application.inject<CollectionRepository>()
                    val collectionBooks by application.inject<CollectionBookRepository>()
                    // Gate private-book into Alice's own collection — invisible to Bob.
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
                    // collection, which Bob's default grant targets under pure union.
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

                    val ratings by application.inject<BookRatingRepository>()
                    val recorder by application.inject<ActivityRecorder>()
                    val bus by application.inject<ChangeBus>()
                    val policy by application.inject<BookAccessPolicy>()

                    // Alice rates both books FIRST, in revision order: public, private, then a
                    // non-book sentinel activity bounds each collector — the bus's replay buffer
                    // then serves each subscriber deterministically.
                    ratings.upsert(
                        BookRatingSyncPayload(
                            id = "r-public",
                            bookId = "public-book",
                            userId = aliceId,
                            halfStars = 8,
                            note = null,
                            ratedAt = 0L,
                            updatedAt = 0L,
                            revision = 0L,
                        ),
                    )
                    ratings.upsert(
                        BookRatingSyncPayload(
                            id = "r-private",
                            bookId = "private-book",
                            userId = aliceId,
                            halfStars = 10,
                            note = null,
                            ratedAt = 0L,
                            updatedAt = 0L,
                            revision = 0L,
                        ),
                    )
                    recorder.record(
                        aliceId,
                        ActivityType.SHELF_CREATED,
                        shelfId = "sentinel",
                        shelfName = sentinelMarker,
                    )

                    val memberEvents = mutableListOf<SyncFrame>()
                    rpcFirehose(bus, memberPrincipal(bobId), bookAccessPolicy = { policy })
                        .domainFrames()
                        .filter { it.domain == "book_ratings" || it.domain == "activities" }
                        .onEach { memberEvents += it }
                        .first { it.json.contains(sentinelMarker) }

                    val rootEvents = mutableListOf<SyncFrame>()
                    rpcFirehose(bus, rootPrincipal(aliceId), bookAccessPolicy = { policy })
                        .domainFrames()
                        .filter { it.domain == "book_ratings" || it.domain == "activities" }
                        .onEach { rootEvents += it }
                        .first { it.json.contains(sentinelMarker) }

                    // Member: accessible book's rating present, private withheld.
                    memberEvents.any { it.json.contains(""""bookId":"public-book"""") } shouldBe true
                    memberEvents.none { it.json.contains("private-book") } shouldBe true

                    // Root: bypasses the gate — sees every rating, including the private-book one.
                    rootEvents.any { it.json.contains(""""bookId":"public-book"""") } shouldBe true
                    rootEvents.any { it.json.contains(""""bookId":"private-book"""") } shouldBe true
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
