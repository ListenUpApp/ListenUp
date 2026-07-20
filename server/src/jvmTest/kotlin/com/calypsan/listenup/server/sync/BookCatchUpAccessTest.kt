@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Tests the `accessFiltered` seam on [SyncableRepository.pullSince] / [digest]
 * for the books domain: a member's catch-up and digest are scoped through
 * [BookAccessPolicy.accessibleBookIdsSql], while an admin (null fragment) sees
 * everything.
 *
 * The seam is exercised directly on [BookRepository] — the route just supplies
 * the fragment — so these tests pin the substrate behaviour independent of the
 * HTTP layer.
 */
class BookCatchUpAccessTest :
    FunSpec({

        /** Wires a [BookRepository] plus the 1a collection repos and the policy. */
        fun SqlTestDatabases.fixture(): Fixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(db = sql, bus = bus, registry = registry)
            val seriesRepo = SeriesRepository(db = sql, bus = bus, registry = registry)
            return Fixture(
                bookRepo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = GenreRepository(db = sql, bus = bus, registry = registry),
                    ),
                collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                collectionBookRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                grantRepo =
                    CollectionGrantRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                policy = BookAccessPolicy(sql, driver),
            )
        }

        test("books pullSince excludes a private book for a member") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    // public-book is visible the pure-union way: it lives in ALL_BOOKS and the
                    // member holds a live grant on that system collection.
                    f.makePublic("public-book", memberId = "member")
                    // private-book lives in a private collection owned by a stranger.
                    f.collectionRepo.upsert(collection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    val extra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    extra shouldNotBe null

                    val page = f.bookRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)
                    val ids = page.items.map { it.id }

                    ids shouldContain "public-book"
                    ids shouldNotContain "private-book"
                }
            }
        }

        test("books pullSince includes public + accessible books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("owned-book")
                val f = fixture()
                runTest {
                    // public-book is visible the pure-union way: ALL_BOOKS membership + member grant.
                    f.makePublic("public-book", memberId = "member")
                    // owned-book lives in a collection the member owns.
                    f.collectionRepo.upsert(collection("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(membership("owned-col", "owned-book"))

                    val extra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    val page = f.bookRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)
                    val ids = page.items.map { it.id }

                    ids shouldContainExactlyInAnyOrder listOf("public-book", "owned-book")
                }
            }
        }

        test("books digest is access-scoped per user (member vs admin differ)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    // public-book is visible to the member the pure-union way: ALL_BOOKS + grant.
                    f.makePublic("public-book", memberId = "member")
                    f.collectionRepo.upsert(collection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    // Cursor must cover the seeded books (seedTestBook writes revision = 1).
                    val cursor = 100L
                    val memberExtra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    val adminExtra = f.policy.accessibleBookIdsSql("admin", UserRole.ADMIN)
                    adminExtra shouldBe null

                    val memberDigest = f.bookRepo.digest("member", cursor, extraWhere = memberExtra)
                    val adminDigest = f.bookRepo.digest("admin", cursor, extraWhere = adminExtra)

                    // Member sees only public-book; admin sees both → counts and hashes differ.
                    memberDigest.count shouldBe 1
                    adminDigest.count shouldBe 2
                    memberDigest.hash shouldNotBe adminDigest.hash
                }
            }
        }
    })

private data class Fixture(
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val policy: BookAccessPolicy,
)

private fun collection(
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

private fun membership(
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

/** A live USER read-grant: the member's key into a collection (e.g. the public `ALL_BOOKS`). */
private fun grant(
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

/**
 * Makes [bookId] publicly visible the pure-union way: drop it into the per-library `ALL_BOOKS`
 * system collection and hand [memberId] a live USER grant on that collection. Mirrors the
 * production substrate where every member holds a default `ALL_BOOKS` grant at registration.
 */
private suspend fun Fixture.makePublic(
    bookId: String,
    memberId: String,
) {
    collectionRepo.upsert(collection("all-books", owner = "system"))
    collectionBookRepo.upsert(membership("all-books", bookId))
    grantRepo.upsert(grant("grant-$bookId-$memberId", "all-books", memberId))
}
