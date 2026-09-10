@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for the visibility half of the gate on every [BookServiceImpl] content mutation.
 *
 * `canEdit` defaults to true for every account, so it is a permission, not an access decision:
 * on its own it let a member rewrite — and delete the cover of — a book they cannot see, and
 * probe which ids exist from the reply. Each mutation must therefore also require that the
 * caller can see the book, and report a denial as [BookError.NotFound] — the very answer the
 * same method already gives for an absent book, so the two are indistinguishable.
 *
 * Each case seeds a real database, builds the impl with real repos plus [BookAccessPolicy],
 * scopes a `canEdit` MEMBER via [BookServiceImpl.copyWith], and drives one mutation against a
 * book that lives only in a stranger's private collection. The positive control proves the
 * same member can still mutate a book they own.
 */
class BookServiceImplMutationAccessTest :
    FunSpec({

        /** Builds the unscoped service plus the repos a test needs to seed books and collections. */
        fun SqlTestDatabases.fixture(): MutationFixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(sql, bus, registry)
            val seriesRepo = SeriesRepository(sql, bus, registry)
            val genreRepo = GenreRepository(sql, bus, registry)
            val bookRepo =
                BookRepository(
                    db = sql,
                    driver = driver,
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                )
            val service =
                BookServiceImpl(
                    repo = bookRepo,
                    contributorRepo = contributorRepo,
                    seriesRepo = seriesRepo,
                    coverStorage = CoverStorage(),
                    sql = sql,
                    genreRepo = genreRepo,
                    accessPolicy = BookAccessPolicy(sql, driver),
                    permissionPolicy = UserPermissionPolicy(sql),
                    principal = PrincipalProvider { error("Unscoped — call copyWith") },
                )
            return MutationFixture(
                service = service,
                bookRepo = bookRepo,
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
            )
        }

        /**
         * One entry per content mutation. The inputs are the emptiest each signature accepts — the
         * gate must answer before any of them is looked at, so the deny cannot depend on them.
         */
        val mutations =
            listOf(
                Mutation("updateBook") { id -> updateBook(id, BookUpdate(title = "Rewritten")) },
                Mutation("setBookContributors") { id -> setBookContributors(id, emptyList()) },
                Mutation("setBookChapters") { id -> setBookChapters(id, emptyList()) },
                Mutation("setBookTierLabels") { id -> setBookTierLabels(id, null, null) },
                Mutation("setBookSeries") { id -> setBookSeries(id, emptyList()) },
                Mutation("setBookGenres") { id -> setBookGenres(id, emptyList()) },
                Mutation("setBookCover") { id -> setBookCover(id, ByteArray(0), "image/jpeg") },
                Mutation("deleteBookCover") { id -> deleteBookCover(id) },
            )

        mutations.forEach { mutation ->
            test("${mutation.name} by a canEdit member who cannot see the book is reported as NotFound") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    sql.seedTestUser("member")
                    val f = fixture()
                    runTest {
                        f.bookRepo.upsert(bookPayloadFixture(id = "hidden", title = "Hidden"))
                        // The book lives only in a stranger's private collection — invisible to the member.
                        f.collectionRepo.upsert(mutationCollection("private-col", owner = "stranger"))
                        f.collectionBookRepo.upsert(mutationMembership("private-col", "hidden"))

                        val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                        val result = mutation.call(scoped, BookId("hidden"))

                        val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                        failure.error.shouldBeInstanceOf<BookError.NotFound>()
                    }
                }
            }
        }

        test("updateBook by a canEdit member on a book they own succeeds (control)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "owned", title = "Owned"))
                    f.collectionRepo.upsert(mutationCollection("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(mutationMembership("owned-col", "owned"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.updateBook(BookId("owned"), BookUpdate(title = "Renamed"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }
    })

private data class MutationFixture(
    val service: BookServiceImpl,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

/** A named content mutation, invoked against a scoped service for one book. */
private class Mutation(
    val name: String,
    val call: suspend BookServiceImpl.(BookId) -> AppResult<Unit>,
)

private fun principalFor(
    userId: String,
    role: UserRole,
): PrincipalProvider =
    PrincipalProvider {
        UserPrincipal(
            userId = UserId(userId),
            sessionId = SessionId("session-$userId"),
            role = role,
        )
    }

private fun mutationCollection(
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

private fun mutationMembership(
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
