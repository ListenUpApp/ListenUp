@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.ktor.ext.inject
import java.nio.file.Files

private const val PULL_LIMIT = 100

/**
 * Tests for the Collections-1b sync-row visibility gate: a member syncs only the
 * collections they own / are shared / global-access (and the shares + memberships
 * scoped the same way), never every collection's rows; an admin sees all.
 *
 * Catch-up/digest tests drive the real syncable repositories with the
 * [BookAccessPolicy] fragments (the exact pairing `SyncRoutes` wires), against a
 * Flyway-migrated in-memory database. The firehose test boots the full [module]
 * and asserts a real SSE stream gates a collection event per-subscriber.
 */
class CollectionRowVisibilityTest :
    FunSpec({

        fun makeRepos(dbs: SqlTestDatabases): Triple<CollectionRepository, CollectionGrantRepository, CollectionBookRepository> {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return Triple(
                CollectionRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver),
                CollectionGrantRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver),
                CollectionBookRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver),
            )
        }

        // ---- Catch-up / digest seam ----

        test("collections catch-up returns only owned + shared for a member; inbox excluded") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestUser("stranger")
                runTest {
                    val (collections, shares, _) = makeRepos(this@withSqlDatabase)

                    collections.upsert(collectionFixture("owned", owner = "member"))
                    collections.upsert(collectionFixture("shared", owner = "stranger"))
                    collections.upsert(collectionFixture("private", owner = "stranger"))
                    collections.upsert(collectionFixture("inbox", owner = "admin", isInbox = true))
                    shares.upsert(shareFixture("share1", "shared", sharedWith = "member"))

                    val policy = BookAccessPolicy(sql, driver)
                    val frag = policy.accessibleCollectionIdsSql("member", UserRole.MEMBER)

                    val page = collections.pullSince(userId = null, cursor = 0, limit = PULL_LIMIT, extraWhere = frag)

                    page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("owned", "shared")
                }
            }
        }

        test("collection_shares catch-up returns only the member's own shares") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestUser("stranger")
                sql.seedTestUser("other")
                runTest {
                    val (collections, shares, _) = makeRepos(this@withSqlDatabase)

                    // member owns "owned"; a share on it (naming "other") is visible to member as owner.
                    collections.upsert(collectionFixture("owned", owner = "member"))
                    collections.upsert(collectionFixture("strangers", owner = "stranger"))
                    shares.upsert(shareFixture("s-naming-member", "strangers", sharedWith = "member"))
                    shares.upsert(shareFixture("s-on-owned", "owned", sharedWith = "other"))
                    shares.upsert(shareFixture("s-irrelevant", "strangers", sharedWith = "other"))

                    val policy = BookAccessPolicy(sql, driver)
                    val frag = policy.visibleCollectionGrantIdsSql("member", UserRole.MEMBER)

                    val page = shares.pullSince(userId = null, cursor = 0, limit = PULL_LIMIT, extraWhere = frag)

                    page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("s-naming-member", "s-on-owned")
                }
            }
        }

        test("collection_books catch-up returns only memberships in accessible collections") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestUser("stranger")
                sql.seedTestBook("b1")
                sql.seedTestBook("b2")
                runTest {
                    val (collections, shares, memberships) = makeRepos(this@withSqlDatabase)

                    collections.upsert(collectionFixture("owned", owner = "member"))
                    collections.upsert(collectionFixture("private", owner = "stranger"))
                    memberships.upsert(membershipFixture("owned", "b1"))
                    memberships.upsert(membershipFixture("private", "b2"))

                    val policy = BookAccessPolicy(sql, driver)
                    val frag = policy.accessibleCollectionBookIdsSql("member", UserRole.MEMBER)

                    val page = memberships.pullSince(userId = null, cursor = 0, limit = PULL_LIMIT, extraWhere = frag)

                    page.items.map { CollectionBookId(it.collectionId, it.bookId).asString() } shouldContainExactlyInAnyOrder
                        listOf("owned:b1")
                }
            }
        }

        test("admin collections catch-up returns all (incl. inbox)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin")
                sql.seedTestUser("stranger")
                runTest {
                    val (collections, _, _) = makeRepos(this@withSqlDatabase)

                    collections.upsert(collectionFixture("private", owner = "stranger"))
                    collections.upsert(collectionFixture("inbox", owner = "admin", isInbox = true))

                    val policy = BookAccessPolicy(sql, driver)
                    val frag = policy.accessibleCollectionIdsSql("admin", UserRole.ADMIN)

                    // Admin → null fragment → no filter → every row.
                    frag shouldBe null
                    val page = collections.pullSince(userId = null, cursor = 0, limit = PULL_LIMIT, extraWhere = frag)

                    page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("private", "inbox")
                }
            }
        }

        // ---- Firehose seam ----

        test("firehose drops a collection event for a member with no access to it") {
            val libraryRoot = Files.createTempDirectory("listenup-fh-coll-access-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseClient()

                    client.mintRootToken()
                    val member = client.registerMember()
                    seedTestLibraryAndFolder()
                    val collections by application.inject<CollectionRepository>()
                    val grants by application.inject<CollectionGrantRepository>()

                    // The visible control is a regular (non-system) collection the member is
                    // granted on — under pure union, collection-domain visibility comes from
                    // ownership or a live grant (there is no global-access branch). Grant it
                    // before the firehose subscription so canAccessCollection sees it at delivery.
                    collections.upsert(collectionFixture("shared-col", owner = "stranger"))
                    grants.upsert(shareFixture("share-shared-col", "shared-col", sharedWith = member.userId))

                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(member.token) },
                    ) {
                        coroutineScope {
                            // The first collections event the member sees must be the granted
                            // (shared) one — never the stranger's private one.
                            val deferred = async { incoming.first { it.event == "collections" } }
                            collections.upsert(collectionFixture("private-col", owner = "stranger"))
                            // Re-upsert the granted collection to publish a firehose event for it.
                            collections.upsert(collectionFixture("shared-col", owner = "stranger"))
                            val event = deferred.await()

                            event.event shouldBe "collections"
                            event.data!!.contains(""""id":"shared-col"""") shouldBe true
                            event.data!!.contains(""""id":"private-col"""") shouldBe false
                        }
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun io.ktor.server.testing.ApplicationTestBuilder.sseClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
        install(SSE)
    }

private suspend fun HttpClient.mintRootToken(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

private data class MemberPrincipal(
    val token: String,
    val userId: String,
)

private suspend fun HttpClient.registerMember(): MemberPrincipal {
    val result =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("member@x", "y".repeat(8), "Member"))
        }.body<AppResult<RegisterResult>>()
            .let { it as AppResult.Success<RegisterResult> }
            .data
    val session = (result as RegisterResult.Authenticated).session
    return MemberPrincipal(token = session.accessToken.value, userId = session.user.id.value)
}

private fun collectionFixture(
    id: String,
    owner: String,
    isInbox: Boolean = false,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = isInbox,
        revision = 0L,
        updatedAt = 0L,
    )

private fun shareFixture(
    id: String,
    collectionId: String,
    sharedWith: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = sharedWith,
        sharedByUserId = "owner",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membershipFixture(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "${collectionId}:${bookId}",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
