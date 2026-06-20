package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Access-gate tests for [SearchServiceImpl]. A member must never see an inaccessible
 * book in the result list OR in the facet counts — facet counts leak existence just
 * as surely as a result row, so both are gated by the one matched-books subquery.
 *
 * Each test seeds a real in-memory database, scopes the caller via
 * [SearchServiceImpl.copyWith] + a [PrincipalProvider] stub, and asserts the gate's
 * decision over both surfaces.
 */
class SearchServiceAccessTest :
    FunSpec({

        /** Repos a test needs to put a book in a collection and grant a member on it. */
        fun Database.collectionRepos(): Triple<CollectionRepository, CollectionBookRepository, CollectionGrantRepository> {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return Triple(
                CollectionRepository(db = this, bus = bus, registry = registry),
                CollectionBookRepository(db = this, bus = bus, registry = registry),
                CollectionGrantRepository(db = this, bus = bus, registry = registry),
            )
        }

        test("search omits a private book the member can't access") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "hidden", "Dragon Hidden", lib)
                seedBook(db, "public", "Dragon Public", lib)
                val (colRepo, colBookRepo) = db.collectionRepos()
                runTest {
                    // "hidden" lives only in a stranger-owned private collection → invisible to the member.
                    colRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    colBookRepo.upsert(membership("private-col", "hidden"))
                    // "public" is reachable the simplest pure-union way: a collection the member owns
                    // (the owner branch needs no grant, no system user).
                    colRepo.upsert(privateCollection("owned-col", owner = "member"))
                    colBookRepo.upsert(membership("owned-col", "public"))

                    val service = SearchServiceImpl(db = db).copyWith(memberPrincipal("member"))
                    val r = service.search(SearchQuery(text = "Dragon")) as AppResult.Success<SearchResults>

                    r.data.books.map { it.id.value } shouldBe listOf("public")
                }
            }
        }

        test("search facet counts exclude inaccessible books") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                // Both books share the Fantasy genre + an author, but "hidden" is private.
                seedBook(db, "hidden", "Dragon Hidden", lib)
                seedBook(db, "public", "Dragon Public", lib)
                seedGenre(db, "g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                linkBookGenre(db, "hidden", "g-fan")
                linkBookGenre(db, "public", "g-fan")
                seedContributor(db, "c1", "Some Author")
                seedBookContributor(db, "hidden", "c1", "author", 0)
                seedBookContributor(db, "public", "c1", "author", 0)
                val (colRepo, colBookRepo) = db.collectionRepos()
                runTest {
                    // "hidden" lives only in a stranger-owned private collection → invisible to the member.
                    colRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    colBookRepo.upsert(membership("private-col", "hidden"))
                    // "public" is reachable via a member-owned collection (owner branch, no grant).
                    colRepo.upsert(privateCollection("owned-col", owner = "member"))
                    colBookRepo.upsert(membership("owned-col", "public"))

                    val service = SearchServiceImpl(db = db).copyWith(memberPrincipal("member"))
                    val r = service.search(SearchQuery(text = "Dragon")) as AppResult.Success<SearchResults>

                    // Only the accessible book counts in every facet dimension.
                    r.data.facets.types.books shouldBe 1
                    r.data.facets.genres
                        .first { it.key == "fantasy" }
                        .count shouldBe 1
                    r.data.facets.authors
                        .first { it.key == "c1" }
                        .count shouldBe 1
                }
            }
        }

        test("admin search includes private/inbox books") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "hidden", "Dragon Hidden", lib)
                seedBook(db, "public", "Dragon Public", lib)
                val (colRepo, colBookRepo) = db.collectionRepos()
                runTest {
                    colRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    colBookRepo.upsert(membership("private-col", "hidden"))

                    val service = SearchServiceImpl(db = db).copyWith(adminPrincipal("admin"))
                    val r = service.search(SearchQuery(text = "Dragon")) as AppResult.Success<SearchResults>

                    r.data.books
                        .map { it.id.value }
                        .sorted() shouldBe listOf("hidden", "public")
                    r.data.facets.types.books shouldBe 2
                }
            }
        }

        test("search includes accessible books for a member: ALL_BOOKS-granted + owned") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                db.seedTestUser("member")
                // Public-via-ALL_BOOKS + owned collection book — both reachable under pure union.
                seedBook(db, "public", "Dragon Public", lib)
                seedBook(db, "owned", "Dragon Owned", lib)
                val (colRepo, colBookRepo, grantRepo) = db.collectionRepos()
                runTest {
                    // ALL_BOOKS membership + the member's grant = the public substrate.
                    colRepo.upsert(allBooksCollection("all-books"))
                    colBookRepo.upsert(membership("all-books", "public"))
                    grantRepo.upsert(share("g1", "all-books", "member"))

                    colRepo.upsert(privateCollection("owned-col", owner = "member"))
                    colBookRepo.upsert(membership("owned-col", "owned"))

                    val service = SearchServiceImpl(db = db).copyWith(memberPrincipal("member"))
                    val r = service.search(SearchQuery(text = "Dragon")) as AppResult.Success<SearchResults>

                    r.data.books
                        .map { it.id.value }
                        .sorted() shouldBe listOf("owned", "public")
                    r.data.facets.types.books shouldBe 2
                    r.data.books shouldHaveSize 2
                }
            }
        }
    })

// ── principal helpers ──────────────────────────────────────────────────────────

private fun memberPrincipal(userId: String): PrincipalProvider = principalFor(userId, UserRole.MEMBER)

private fun adminPrincipal(userId: String): PrincipalProvider = principalFor(userId, UserRole.ADMIN)

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

private fun privateCollection(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "lib1",
        ownerId = owner,
        name = id,
        isInbox = false,
        isGlobalAccess = false,
        revision = 0L,
        updatedAt = 0L,
    )

/** The per-library ALL_BOOKS system collection — the public substrate, owned by the system sentinel. */
private fun allBooksCollection(id: String): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "lib1",
        ownerId = "system",
        name = "All Books",
        isInbox = false,
        isGlobalAccess = false,
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

private fun share(
    id: String,
    collectionId: String,
    userId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = userId,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
        deletedAt = null,
    )

// ── seed helpers (mirror SearchServiceImplTest) ────────────────────────────────

private fun seedLibrary(
    db: Database,
    libraryId: String = "lib1",
    path: String = "/tmp/search-access-test",
    folderId: String = "folder1",
): String {
    val now = System.currentTimeMillis()
    transaction(db) {
        LibraryTable.insert {
            it[LibraryTable.id] = libraryId
            it[LibraryTable.name] = "Test Library"
            it[LibraryTable.metadataPrecedence] = "embedded"
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        LibraryFolderTable.insert {
            it[LibraryFolderTable.id] = folderId
            it[LibraryFolderTable.libraryId] = libraryId
            it[LibraryFolderTable.rootPath] = path
            it[LibraryFolderTable.createdAt] = now
            it[LibraryFolderTable.updatedAt] = now
            it[LibraryFolderTable.revision] = 0L
            it[LibraryFolderTable.deletedAt] = null
        }
    }
    return libraryId
}

private fun seedBook(
    db: Database,
    bookId: String,
    title: String,
    libraryId: String,
    folderId: String = "folder1",
) {
    val now = System.currentTimeMillis()
    val rowid = (bookId.hashCode().toLong().let { if (it < 0) -it else it } % 999_999L) + 1L
    transaction(db) {
        BookTable.insert {
            it[BookTable.id] = bookId
            it[BookTable.libraryId] = libraryId
            it[BookTable.folderId] = folderId
            it[BookTable.title] = title
            it[BookTable.sortTitle] = title
            it[BookTable.totalDuration] = 3_600_000L
            it[BookTable.rootRelPath] = "$bookId/book.mp3"
            it[BookTable.scannedAt] = now
            it[BookTable.revision] = 1L
            it[BookTable.createdAt] = now
            it[BookTable.updatedAt] = now
            it[BookTable.deletedAt] = null
        }
        BookSearchMapTable.insert {
            it[BookSearchMapTable.bookId] = bookId
            it[BookSearchMapTable.rowid] = rowid.toInt()
        }
        val tx = TransactionManager.current()
        tx.exec("DELETE FROM book_search WHERE rowid = $rowid")
        val cols = "rowid, title, subtitle, description, contributor_names, series_names"
        tx.exec(
            stmt = "INSERT INTO book_search($cols) VALUES($rowid, ?, '', '', '', '')",
            args = listOf(TextColumnType() to title),
        )
    }
}

private fun seedContributor(
    db: Database,
    contributorId: String,
    name: String,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        ContributorTable.insert {
            it[ContributorTable.id] = contributorId
            it[ContributorTable.normalizedName] = name.lowercase()
            it[ContributorTable.name] = name
            it[ContributorTable.sortName] = null
            it[ContributorTable.revision] = 1L
            it[ContributorTable.createdAt] = now
            it[ContributorTable.updatedAt] = now
        }
    }
}

private fun seedBookContributor(
    db: Database,
    bookId: String,
    contributorId: String,
    role: String,
    ordinal: Int,
) {
    transaction(db) {
        BookContributorTable.insert {
            it[BookContributorTable.bookId] = bookId
            it[BookContributorTable.contributorId] = contributorId
            it[BookContributorTable.role] = role
            it[BookContributorTable.creditedAs] = null
            it[BookContributorTable.ordinal] = ordinal
        }
    }
}

private fun seedGenre(
    db: Database,
    id: String,
    slug: String,
    path: String,
    name: String = slug,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        GenreTable.insert {
            it[GenreTable.id] = id
            it[GenreTable.name] = name
            it[GenreTable.slug] = slug
            it[GenreTable.path] = path
            it[GenreTable.revision] = 1L
            it[GenreTable.createdAt] = now
            it[GenreTable.updatedAt] = now
        }
    }
}

private fun linkBookGenre(
    db: Database,
    bookId: String,
    genreId: String,
) {
    transaction(db) {
        BookGenreTable.insert {
            it[BookGenreTable.bookId] = bookId
            it[BookGenreTable.genreId] = genreId
        }
    }
}
