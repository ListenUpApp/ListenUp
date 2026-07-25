package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.publicAuthService

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.TargetedMatch
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * Regression guard for the `COLLECTION_ID` sibling of the `BOOK_ID` allowlist bug (fixed for the
 * REST-era `?bookIds=` route in `721a28cd1`): a domain whose root table has no `collection_id`
 * column must reject a targeted [TargetedMatch.COLLECTION_ID] fetch with
 * [SyncError.UnsupportedMatch], never a crash.
 */
class CollectionIdsAllowlistTest :
    FunSpec({

        test(
            "pullByIds(books, COLLECTION_ID) (driver-wired, no collection_id column) " +
                "fails with SyncError.UnsupportedMatch, not a crash",
        ) {
            val libraryRoot = Files.createTempDirectory("listenup-collids-allowlist-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintRootToken()

                    authedService<SyncStreamService>(token)
                        .pullByIds("books", TargetedMatch.COLLECTION_ID, listOf("c1", "c2"))
                        .shouldFailWith<SyncError.UnsupportedMatch>()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("pullByIds(collection_books, COLLECTION_ID) still returns the matching rows") {
            val libraryRoot = Files.createTempDirectory("listenup-collids-allowlist-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintRootToken()
                    seedTestLibraryAndFolder()

                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestBook("b1")
                    sql.seedTestBook("b2")

                    val collections by application.inject<CollectionRepository>()
                    val memberships by application.inject<CollectionBookRepository>()

                    collections.upsert(collectionFixture("col-a"))
                    collections.upsert(collectionFixture("col-b"))
                    memberships.upsert(membershipFixture("col-a", "b1"))
                    memberships.upsert(membershipFixture("col-b", "b2"))

                    val page =
                        authedService<SyncStreamService>(token)
                            .pullByIds("collection_books", TargetedMatch.COLLECTION_ID, listOf("col-a"))
                            .shouldSucceed()
                    page.rows(CollectionBookSyncPayload.serializer()).map { it.bookId } shouldContainExactly
                        listOf("b1")
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private suspend fun ApplicationTestBuilder.mintRootToken(): String =
    publicAuthService()
        .setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

private fun collectionFixture(id: String): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = "root",
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membershipFixture(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
