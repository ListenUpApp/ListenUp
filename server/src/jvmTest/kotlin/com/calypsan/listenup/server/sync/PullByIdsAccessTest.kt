@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.Tag
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
import kotlinx.coroutines.test.runTest

/**
 * Pins the read half of the scoped `AccessChanged` delta: [SqlSyncableRepository.pullByIds] on the
 * access-gated `books` and `collection_books` domains. The invariant — a returned id is one the
 * caller can still see; an asked-about id that does not come back is gone or no longer accessible
 * (the client's cue to tombstone it) — is what makes the delta a sound, leak-free replacement for a
 * full re-derive. Exercised directly on the repositories, independent of the HTTP layer.
 */
class PullByIdsAccessTest :
    FunSpec({

        fun SqlTestDatabases.pbiFixture(): PbiFixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(db = sql, bus = bus, registry = registry)
            val seriesRepo = SeriesRepository(db = sql, bus = bus, registry = registry)
            return PbiFixture(
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
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
                grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver),
                policy = BookAccessPolicy(sql, driver),
            )
        }

        test("books pullByIds returns an accessible book and omits an inaccessible one") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = pbiFixture()
                runTest {
                    f.pbiMakePublic("public-book", memberId = "member")
                    f.collectionRepo.upsert(pbiCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(pbiMembership("private-col", "private-book"))

                    val extra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    val page =
                        f.bookRepo.pullByIds(
                            userId = "member",
                            matchColumn = "id",
                            matchValues = listOf("public-book", "private-book"),
                            extraWhere = extra,
                        )
                    val ids = page.items.map { it.id }

                    // public-book comes back (upsert); private-book does not (client tombstones it).
                    ids shouldContain "public-book"
                    ids shouldNotContain "private-book"
                }
            }
        }

        test("books pullByIds delivers a tombstone so a missed deletion converges") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("doomed-book")
                val f = pbiFixture()
                runTest {
                    f.pbiMakePublic("doomed-book", memberId = "member")
                    f.bookRepo.softDelete(BookId("doomed-book"))

                    val extra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    val page =
                        f.bookRepo.pullByIds(
                            userId = "member",
                            matchColumn = "id",
                            matchValues = listOf("doomed-book"),
                            extraWhere = extra,
                        )

                    // A soft-deleted row is not "accessible", but includeTombstones delivers it so the
                    // client learns to remove it — the catch-up tombstone contract, unchanged.
                    page.items.map { it.id } shouldContain "doomed-book"
                }
            }
        }

        test("books pullByIds for an admin (null filter) returns every requested row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = pbiFixture()
                runTest {
                    f.pbiMakePublic("public-book", memberId = "member")
                    f.collectionRepo.upsert(pbiCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(pbiMembership("private-col", "private-book"))

                    val adminExtra = f.policy.accessibleBookIdsSql("admin", UserRole.ADMIN)
                    val page =
                        f.bookRepo.pullByIds(
                            userId = "admin",
                            matchColumn = "id",
                            matchValues = listOf("public-book", "private-book"),
                            extraWhere = adminExtra,
                        )

                    page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("public-book", "private-book")
                }
            }
        }

        test("tags pullByIds returns the requested tag by id (ungated domain, no access driver)") {
            // The DRIFT-1 dead-letter heal re-fetches a curation entity by id. Tags is an ungated
            // (userScoped/global) domain with no wired access driver — this used to short-circuit to
            // an empty page, so the heal had nothing to apply. It must now serve the row directly.
            withSqlDatabase {
                val tagRepo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    tagRepo.upsert(pbiTag("tag-x", "Fantasy", "fantasy"))
                    val page =
                        tagRepo.pullByIds(
                            userId = "member",
                            matchColumn = "id",
                            matchValues = listOf("tag-x"),
                            extraWhere = null,
                        )
                    page.items.map { it.id } shouldContain "tag-x"
                }
            }
        }

        test("tags pullByIds delivers a tombstone for a soft-deleted tag so a heal converges") {
            withSqlDatabase {
                val tagRepo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    tagRepo.upsert(pbiTag("tag-d", "Doomed", "doomed"))
                    tagRepo.softDelete("tag-d", clientOpId = null)
                    val page =
                        tagRepo.pullByIds(
                            userId = "member",
                            matchColumn = "id",
                            matchValues = listOf("tag-d"),
                            extraWhere = null,
                        )
                    // The soft-deleted row comes back (minimized) so a heal of a server-deleted entity
                    // learns to tombstone the local phantom rather than resurrecting it.
                    page.items.map { it.id } shouldContain "tag-d"
                }
            }
        }

        test("tags pullByIds returns empty for a non-id match column on an ungated domain") {
            withSqlDatabase {
                val tagRepo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    tagRepo.upsert(pbiTag("tag-x", "Fantasy", "fantasy"))
                    val page =
                        tagRepo.pullByIds(
                            userId = "member",
                            matchColumn = "book_id",
                            matchValues = listOf("tag-x"),
                            extraWhere = null,
                        )
                    // Ungated domains are only queried by "id"; anything else answers empty, never 500s.
                    page.items shouldBe emptyList()
                }
            }
        }

        test("collection_books pullByIds by collection_id returns only accessible memberships") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("owned-book")
                sql.seedTestBook("private-book")
                val f = pbiFixture()
                runTest {
                    f.collectionRepo.upsert(pbiCollection("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(pbiMembership("owned-col", "owned-book"))
                    f.collectionRepo.upsert(pbiCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(pbiMembership("private-col", "private-book"))

                    val extra = f.policy.accessibleCollectionBookIdsSql("member", UserRole.MEMBER)
                    val page =
                        f.collectionBookRepo.pullByIds(
                            userId = "member",
                            matchColumn = "collection_id",
                            matchValues = listOf("owned-col", "private-col"),
                            extraWhere = extra,
                        )

                    val memberships = page.items.map { it.collectionId to it.bookId }
                    memberships shouldContain ("owned-col" to "owned-book")
                    memberships shouldNotContain ("private-col" to "private-book")
                }
            }
        }
    })

private data class PbiFixture(
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val policy: BookAccessPolicy,
)

private fun pbiTag(
    id: String,
    name: String,
    slug: String,
): Tag = Tag(id = id, name = name, slug = slug, revision = 0L, updatedAt = 0L, deletedAt = null)

private fun pbiCollection(
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

private fun pbiMembership(
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

private fun pbiGrant(
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

private suspend fun PbiFixture.pbiMakePublic(
    bookId: String,
    memberId: String,
) {
    collectionRepo.upsert(pbiCollection("all-books", owner = "system"))
    collectionBookRepo.upsert(pbiMembership("all-books", bookId))
    grantRepo.upsert(pbiGrant("grant-$bookId-$memberId", "all-books", memberId))
}
