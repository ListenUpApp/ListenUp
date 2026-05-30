@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

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
        fun Database.fixture(): Fixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(db = this, bus = bus, registry = registry)
            val seriesRepo = SeriesRepository(db = this, bus = bus, registry = registry)
            return Fixture(
                bookRepo =
                    BookRepository(
                        db = this,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                    ),
                collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry),
                collectionBookRepo = CollectionBookRepository(db = this, bus = bus, registry = registry),
                policy = BookAccessPolicy(this),
            )
        }

        test("books pullSince excludes a private book for a member") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("public-book")
                seedTestBook("private-book")
                val f = fixture()
                runTest {
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

        test("books pullSince includes uncollected + accessible books") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("uncollected-book")
                seedTestBook("owned-book")
                val f = fixture()
                runTest {
                    // owned-book lives in a collection the member owns.
                    f.collectionRepo.upsert(collection("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(membership("owned-col", "owned-book"))

                    val extra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    val page = f.bookRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)
                    val ids = page.items.map { it.id }

                    ids shouldContainExactlyInAnyOrder listOf("uncollected-book", "owned-book")
                }
            }
        }

        test("books digest is access-scoped per user (member vs admin differ)") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("public-book")
                seedTestBook("private-book")
                val f = fixture()
                runTest {
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
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
