@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest

/**
 * The `book_tags` sync domain is access-filtered on every read path.
 *
 * This domain leaked for the whole of its existence. A junction row is `(bookId, tagId)` — and
 * [BookTagRepository.minimizeTombstone] already strips exactly that pair from TOMBSTONES, with the
 * reasoning spelled out in its KDoc: "a member who never had access to bookId would otherwise learn
 * the association from the tombstone alone." The live row carried the same pair, ungated, so the
 * tombstone protection was moot — a member's catch-up enumerated the ids of every book on the
 * server along with how each was classified, and those rows landed in their Room database.
 *
 * Every assertion carries a **visible control** (a junction the member legitimately sees), so a
 * filter that returned nothing at all would fail rather than pass.
 *
 * **Scope, stated honestly.** These tests drive the repository seam and pass the fragment
 * explicitly, so they prove the FRAGMENT is correct — they do not prove it is wired into the route.
 * Deleting this domain's `ACCESS_FILTERS` entry leaves them green. That wiring is pinned separately
 * by `AccessGateParitySpec`, which compares the server's declared per-row gated set against the
 * client's `AccessGate` domains and fails on either half going missing.
 */
class BookTagCatchUpAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): JunctionFixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return JunctionFixture(
                bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry, driver = driver),
                tagRepo = TagRepository(db = sql, bus = bus, registry = registry),
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo =
                    CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
                grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver),
                policy = BookAccessPolicy(sql, driver),
            )
        }

        suspend fun JunctionFixture.seed() {
            tagRepo.upsert(Tag(id = "t-noir", name = "noir", slug = "noir", revision = 0L, updatedAt = 0L))
            // Visible control.
            collectionRepo.upsert(col("all-books", owner = "system"))
            collectionBookRepo.upsert(member("all-books", "public-book"))
            grantRepo.upsert(readGrant("g-member", "all-books", "member"))
            // Hidden: a stranger's private collection.
            collectionRepo.upsert(col("stranger-col", owner = "stranger"))
            collectionBookRepo.upsert(member("stranger-col", "private-book"))
            bookTagRepo.upsert(junction("bt-public", "public-book", "t-noir"))
            bookTagRepo.upsert(junction("bt-private", "private-book", "t-noir"))
        }

        test("a member's catch-up page omits the junction of a book they cannot see") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.seed()

                    val extra = f.policy.accessibleBookTagIdsSql("member", UserRole.MEMBER)
                    extra shouldNotBe null

                    val page = f.bookTagRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)
                    val ids = page.items.map { it.id }

                    ids shouldContain "bt-public"
                    ids shouldNotContain "bt-private"
                }
            }
        }

        test("an admin's catch-up page still carries every junction") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin")
                // seed() grants to "member"; the users FK requires the row to exist even in the
                // admin case, where the grant itself is irrelevant to what admin can see.
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.seed()

                    // Null fragment == unfiltered. The ROOT/ADMIN bypass is structural.
                    f.policy.accessibleBookTagIdsSql("admin", UserRole.ADMIN) shouldBe null

                    val page = f.bookTagRepo.pullSince("admin", cursor = 0L, limit = 100, extraWhere = null)
                    val ids = page.items.map { it.id }

                    ids shouldContain "bt-public"
                    ids shouldContain "bt-private"
                }
            }
        }

        test("the digest a member reconciles against counts only their visible junctions") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.seed()

                    // High enough to cover every seeded revision, as the books model does.
                    val cursor = 100L
                    val memberDigest =
                        f.bookTagRepo.digest(
                            "member",
                            cursor,
                            extraWhere = f.policy.accessibleBookTagIdsSql("member", UserRole.MEMBER),
                        )
                    val adminDigest = f.bookTagRepo.digest("admin", cursor, extraWhere = null)

                    // The member's digest must count ONLY their visible junction. Anything else and
                    // they reconcile forever against rows the server will never send them.
                    memberDigest.count shouldBe 1
                    adminDigest.count shouldBe 2
                    memberDigest.hash shouldNotBe adminDigest.hash
                }
            }
        }
    })

private data class JunctionFixture(
    val bookTagRepo: BookTagRepository,
    val tagRepo: TagRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val policy: BookAccessPolicy,
)

private fun junction(
    id: String,
    bookId: String,
    tagId: String,
): BookTagSyncPayload = BookTagSyncPayload(id = id, bookId = bookId, tagId = tagId, createdAt = 0L, revision = 0L)

private fun col(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun member(
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

private fun readGrant(
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
