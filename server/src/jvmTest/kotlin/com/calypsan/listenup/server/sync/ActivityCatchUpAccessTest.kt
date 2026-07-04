@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * The sync-surface ACL proof for the `activities` domain: a member's catch-up ([pullSince]) and
 * drift [digest] are scoped through [activitiesAccessFilter] — a book-bearing row is visible iff the
 * caller can access that book, a `book_id == null` row is always visible, and an admin (null
 * fragment) sees everything.
 *
 * Where [com.calypsan.listenup.server.api.ActivityAclE2ETest] proves the *feed RPC* ACL, this pins
 * the *sync channel*: the exact seam a missed live event self-heals through. It clones
 * [BookCatchUpAccessTest], but exercises the activity-specific `book_id IS NULL OR book_id IN
 * (accessible)` shape (books gate on the id itself; activities gate on the row's book_id) via the
 * SAME [activitiesAccessFilter] the route uses — so a drift between test and production is impossible.
 */
class ActivityCatchUpAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): ActivityAclFixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val activityRepo = ActivitySyncRepository(db = sql, bus = bus, registry = registry, driver = driver)
            return ActivityAclFixture(
                activityRepo = activityRepo,
                recorder = ActivityRecorder(syncRepo = activityRepo),
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
                grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver),
                policy = BookAccessPolicy(sql, driver),
            )
        }

        test("activities pullSince gives a member the public + non-book rows, never the private-book row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.makePublic("public-book", memberId = "member")
                    f.collectionRepo.upsert(aclCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(aclMembership("private-col", "private-book"))

                    // Three activities: one on each book + one non-book (always public).
                    f.recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "public-book")
                    f.recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "private-book")
                    f.recorder.record(userId = "alice", type = ActivityType.USER_JOINED)

                    val extra = activitiesAccessFilter(f.policy, "member", UserRole.MEMBER)
                    extra shouldNotBe null

                    val bookIds =
                        f.activityRepo
                            .pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)
                            .items
                            .map { it.bookId }

                    bookIds shouldContain "public-book"
                    bookIds shouldContain null // the non-book USER_JOINED row is public
                    bookIds shouldNotContain "private-book"
                }
            }
        }

        test("activities digest is access-scoped per user (member sees 2, admin sees all 3)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.makePublic("public-book", memberId = "member")
                    f.collectionRepo.upsert(aclCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(aclMembership("private-col", "private-book"))

                    f.recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "public-book")
                    f.recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "private-book")
                    f.recorder.record(userId = "alice", type = ActivityType.USER_JOINED)

                    val cursor = 1_000L
                    val memberExtra = activitiesAccessFilter(f.policy, "member", UserRole.MEMBER)
                    val adminExtra = activitiesAccessFilter(f.policy, "admin", UserRole.ADMIN)
                    adminExtra shouldBe null // ROOT/ADMIN unconstrained

                    val memberDigest = f.activityRepo.digest("member", cursor, extraWhere = memberExtra)
                    val adminDigest = f.activityRepo.digest("admin", cursor, extraWhere = adminExtra)

                    memberDigest.count shouldBe 2 // public-book + non-book
                    adminDigest.count shouldBe 3 // + private-book
                    memberDigest.hash shouldNotBe adminDigest.hash
                }
            }
        }
    })

private data class ActivityAclFixture(
    val activityRepo: ActivitySyncRepository,
    val recorder: ActivityRecorder,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val policy: BookAccessPolicy,
)

private fun aclCollection(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        revision = 0L,
        updatedAt = 0L,
    )

private fun aclMembership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun aclGrant(
    id: String,
    collectionId: String,
    memberId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = memberId,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
    )

/** Makes [bookId] publicly visible the pure-union way (ALL_BOOKS membership + a member USER grant). */
private suspend fun ActivityAclFixture.makePublic(
    bookId: String,
    memberId: String,
) {
    collectionRepo.upsert(aclCollection("all-books", owner = "system"))
    collectionBookRepo.upsert(aclMembership("all-books", bookId))
    grantRepo.upsert(aclGrant("grant-$bookId-$memberId", "all-books", memberId))
}
