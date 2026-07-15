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
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

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
        fun SqlTestDatabases.collectionRepos(): Triple<CollectionRepository, CollectionBookRepository, CollectionGrantRepository> {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return Triple(
                CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
                CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver),
            )
        }

        test("search omits a private book the member can't access") {
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("hidden", "Dragon Hidden", lib)
                seedBook("public", "Dragon Public", lib)
                val (colRepo, colBookRepo) = collectionRepos()
                runTest {
                    // "hidden" lives only in a stranger-owned private collection → invisible to the member.
                    colRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    colBookRepo.upsert(membership("private-col", "hidden"))
                    // "public" is reachable the simplest pure-union way: a collection the member owns
                    // (the owner branch needs no grant, no system user).
                    colRepo.upsert(privateCollection("owned-col", owner = "member"))
                    colBookRepo.upsert(membership("owned-col", "public"))

                    val service = SearchServiceImpl(db = sql, driver = driver).copyWith(memberPrincipal("member"))
                    val r = service.search(SearchQuery(text = "Dragon")) as AppResult.Success<SearchResults>

                    r.data.books.map { it.id.value } shouldBe listOf("public")
                }
            }
        }

        test("search facet counts exclude inaccessible books") {
            withSqlDatabase {
                val lib = seedLibrary(sql)
                // Both books share the Fantasy genre + an author, but "hidden" is private.
                seedBook("hidden", "Dragon Hidden", lib)
                seedBook("public", "Dragon Public", lib)
                seedGenre(sql, "g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                linkBookGenre(sql, "hidden", "g-fan")
                linkBookGenre(sql, "public", "g-fan")
                seedContributor(sql, "c1", "Some Author")
                seedBookContributor(sql, "hidden", "c1", "author", 0)
                seedBookContributor(sql, "public", "c1", "author", 0)
                val (colRepo, colBookRepo) = collectionRepos()
                runTest {
                    // "hidden" lives only in a stranger-owned private collection → invisible to the member.
                    colRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    colBookRepo.upsert(membership("private-col", "hidden"))
                    // "public" is reachable via a member-owned collection (owner branch, no grant).
                    colRepo.upsert(privateCollection("owned-col", owner = "member"))
                    colBookRepo.upsert(membership("owned-col", "public"))

                    val service = SearchServiceImpl(db = sql, driver = driver).copyWith(memberPrincipal("member"))
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("hidden", "Dragon Hidden", lib)
                seedBook("public", "Dragon Public", lib)
                val (colRepo, colBookRepo) = collectionRepos()
                runTest {
                    colRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    colBookRepo.upsert(membership("private-col", "hidden"))

                    val service = SearchServiceImpl(db = sql, driver = driver).copyWith(adminPrincipal("admin"))
                    val r = service.search(SearchQuery(text = "Dragon")) as AppResult.Success<SearchResults>

                    r.data.books
                        .map { it.id.value }
                        .sorted() shouldBe listOf("hidden", "public")
                    r.data.facets.types.books shouldBe 2
                }
            }
        }

        test("search includes accessible books for a member: ALL_BOOKS-granted + owned") {
            withSqlDatabase {
                val lib = seedLibrary(sql)
                sql.seedTestUser("member")
                // Public-via-ALL_BOOKS + owned collection book — both reachable under pure union.
                seedBook("public", "Dragon Public", lib)
                seedBook("owned", "Dragon Owned", lib)
                val (colRepo, colBookRepo, grantRepo) = collectionRepos()
                runTest {
                    // ALL_BOOKS membership + the member's grant = the public substrate.
                    colRepo.upsert(allBooksCollection("all-books"))
                    colBookRepo.upsert(membership("all-books", "public"))
                    grantRepo.upsert(share("g1", "all-books", "member"))

                    colRepo.upsert(privateCollection("owned-col", owner = "member"))
                    colBookRepo.upsert(membership("owned-col", "owned"))

                    val service = SearchServiceImpl(db = sql, driver = driver).copyWith(memberPrincipal("member"))
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
    sql: ListenUpDatabase,
    libraryId: String = "lib1",
    path: String = "/tmp/search-access-test",
    folderId: String = "folder1",
): String {
    val now = System.currentTimeMillis()
    sql.librariesQueries.insert(
        id = libraryId,
        name = "Test Library",
        metadata_precedence = "embedded",
        access_mode = "shared",
        created_by_user_id = null,
        created_at = now,
        revision = 0L,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
    sql.libraryFoldersQueries.insert(
        id = folderId,
        library_id = libraryId,
        root_path = path,
        created_at = now,
        revision = 0L,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
    return libraryId
}

private fun SqlTestDatabases.seedBook(
    bookId: String,
    title: String,
    libraryId: String,
    folderId: String = "folder1",
) {
    val now = System.currentTimeMillis()
    val rowid = (bookId.hashCode().toLong().let { if (it < 0) -it else it } % 999_999L) + 1L
    sql.booksQueries.insert(
        id = bookId,
        library_id = libraryId,
        folder_id = folderId,
        title = title,
        sort_title = title,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 3_600_000L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        field_provenance = "{}",
        root_rel_path = "$bookId/book.mp3",
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
    sql.bookSearchQueries.insertMap(book_id = bookId, rowid = rowid)
    driver.execute(identifier = null, sql = "DELETE FROM book_search WHERE rowid = $rowid", parameters = 0)
    driver.execute(
        identifier = null,
        sql =
            "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names) " +
                "VALUES($rowid, ?, '', '', '', '')",
        parameters = 1,
        binders = { bindString(0, title) },
    )
}

private fun seedContributor(
    sql: ListenUpDatabase,
    contributorId: String,
    name: String,
) {
    val now = System.currentTimeMillis()
    sql.contributorsQueries.insert(
        id = contributorId,
        normalized_name = name.lowercase(),
        name = name,
        sort_name = null,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
        asin = null,
        description = null,
        image_path = null,
        birth_date = null,
        death_date = null,
        website = null,
    )
}

private fun seedBookContributor(
    sql: ListenUpDatabase,
    bookId: String,
    contributorId: String,
    role: String,
    ordinal: Int,
) {
    sql.bookContributorsQueries.insert(
        book_id = bookId,
        contributor_id = contributorId,
        role = role,
        credited_as = null,
        ordinal = ordinal.toLong(),
    )
}

private fun seedGenre(
    sql: ListenUpDatabase,
    id: String,
    slug: String,
    path: String,
    name: String = slug,
) {
    val now = System.currentTimeMillis()
    sql.genresQueries.insert(
        id = id,
        name = name,
        slug = slug,
        path = path,
        parent_id = null,
        depth = 0L,
        sort_order = 0L,
        color = null,
        description = null,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
}

private fun linkBookGenre(
    sql: ListenUpDatabase,
    bookId: String,
    genreId: String,
) {
    sql.bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = genreId)
}
