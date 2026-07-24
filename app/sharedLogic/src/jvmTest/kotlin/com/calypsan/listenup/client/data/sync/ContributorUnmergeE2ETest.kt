package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private const val COLD_RPC_HANDSHAKE_ATTEMPTS = 4
private const val COLD_RPC_HANDSHAKE_RETRY_DELAY_MS = 250L

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val UNMERGE_TARGET_ID = "unmerge-stephen-king"
private const val UNMERGE_TARGET_NAME = "Stephen King"
private const val ALIAS_NAME = "Richard Bachman"
private const val BACHMAN_BOOK_ONE_ID = "unmerge-b1"
private const val BACHMAN_BOOK_TWO_ID = "unmerge-b2"
private const val KING_BOOK_ID = "unmerge-b3"

/**
 * Tier 3 e2e test for the Books-C2 contributor unmerge: a client-side call to
 * [com.calypsan.listenup.client.domain.repository.ContributorEditRepository.unmergeContributor]
 * crosses the live kotlinx.rpc transport into the in-process `:server`'s
 * `ContributorService`. The server splits [ALIAS_NAME] back out of [UNMERGE_TARGET_ID]:
 * creates a fresh contributor named "Richard Bachman", re-links every `book_contributors`
 * junction row whose `credited_as = "Richard Bachman"` onto the new contributor (clearing
 * `credited_as` — the canonical name IS the alias), leaves all other junctions with the
 * target unchanged, removes the alias from the target's alias set, and emits a burst of
 * SSE events: one `books.Updated` per alias-credited book + one `contributors.Updated` for
 * the target (alias removal) + one `contributors.Created` for the fresh contributor.
 *
 * All of these must land in client Room: alias-credited books' junctions point at the new
 * contributor id, the plain King book stays with the target, the target's
 * `contributor_aliases` no longer contains "Richard Bachman", and the new contributor row
 * exists. The poll witness combines all four conditions so the cascade is only declared
 * complete when SSE has delivered every event.
 *
 * Server-side unmerge semantics (alias removal, junction relink, FTS reindex) are covered
 * by `:server`'s `ContributorServiceImplUnmergeTest`. This file proves the cross-domain
 * wiring + the highest-value invariant — `credited_as = NULL` on the new contributor's
 * junction rows — survives the full RPC → SSE → Room round trip.
 */
class ContributorUnmergeE2ETest :
    FunSpec({

        test(
            "unmergeContributor: new contributor created + alias-credited books relinked " +
                "with creditedAs cleared + plain book stays + alias removed from target",
        ) {
            withClientSyncEngineAgainstServer {
                // Seed the target contributor with the alias already present (post-merge state).
                serverContributorRepository.upsert(
                    ContributorSyncPayload(
                        id = UNMERGE_TARGET_ID,
                        name = UNMERGE_TARGET_NAME,
                        sortName = "King, Stephen",
                        aliases = listOf(ALIAS_NAME),
                        revision = 0L,
                        updatedAt = 0L,
                        createdAt = 0L,
                        deletedAt = null,
                    ),
                )

                val bachmanPayload =
                    BookContributorPayload(
                        id = UNMERGE_TARGET_ID,
                        name = UNMERGE_TARGET_NAME,
                        sortName = null,
                        role = "author",
                        creditedAs = ALIAS_NAME,
                    )
                val kingPayload =
                    BookContributorPayload(
                        id = UNMERGE_TARGET_ID,
                        name = UNMERGE_TARGET_NAME,
                        sortName = null,
                        role = "author",
                        creditedAs = null,
                    )

                // Two books credited as "Richard Bachman" — these must re-link to the new
                // contributor after unmerge, with credited_as cleared.
                serverBookRepository.upsert(
                    bookFixture(
                        id = BACHMAN_BOOK_ONE_ID,
                        title = "The Long Walk",
                        contributors = listOf(bachmanPayload),
                    ),
                )
                serverBookRepository.upsert(
                    bookFixture(
                        id = BACHMAN_BOOK_TWO_ID,
                        title = "Thinner",
                        contributors = listOf(bachmanPayload),
                    ),
                )
                // One plain Stephen King book — must remain with the target unchanged.
                serverBookRepository.upsert(
                    bookFixture(
                        id = KING_BOOK_ID,
                        title = "It",
                        contributors = listOf(kingPayload),
                    ),
                )

                engine.start(currentUserId = "u1")

                // Wait for seed catch-up: all three books' junctions must be present before
                // issuing the unmerge so the relink assertion is unambiguous.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.contributorDao().getByBookId(BACHMAN_BOOK_ONE_ID).isEmpty() ||
                        clientDatabase.contributorDao().getByBookId(BACHMAN_BOOK_TWO_ID).isEmpty() ||
                        clientDatabase.contributorDao().getByBookId(KING_BOOK_ID).isEmpty()
                    ) {
                        // SSE delivery latency is non-deterministic; poll the real query.
                    }
                }

                // Issue the unmerge over the real kotlinx.rpc transport.
                //
                // This is the test's first and only RPC call, so kotlinx.rpc opens its WebSocket
                // lazily right here — a *cold* handshake. Under CI contention that handshake
                // intermittently fails ("Handshake exception, expected status code 101 but was
                // 401") *before* the request reaches `ContributorService`, surfacing as an
                // AppResult.Failure (see the channel's catchingRpcResult fold). The
                // unmerge never executed, so re-firing is safe: a failed handshake leaves no
                // server-side state to collide with. (The test auth provider authenticates every
                // request unconditionally, so the 401 is transport-layer, never a real auth
                // rejection.) Bounded retry removes the transport flake without masking a real
                // failure — a genuine service error returns Failure on every attempt and still
                // fails the test.
                val result =
                    retryOnColdRpcHandshake {
                        contributorEditRepository.unmergeContributor(
                            contributorId = ContributorId(UNMERGE_TARGET_ID),
                            aliasName = ALIAS_NAME,
                        )
                    }
                result.shouldBeInstanceOf<AppResult.Success<ContributorId>>()
                val newId = result.data.value
                newId shouldNotBe UNMERGE_TARGET_ID

                // Unmerge is complete only when ALL of these hold in client Room:
                //  - new contributor row exists,
                //  - target's alias junction no longer contains the alias name,
                //  - alias-credited books' junctions point at the new contributor,
                //  - plain King book's junction still points at the target.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (!unmergeFullyLanded(clientDatabase, newId)) {
                        // SSE delivery latency is non-deterministic; poll until convergence.
                    }
                }

                // Server-side dual assertion — proves the server is driving the client state.
                val finalTarget = serverContributorRepository.findById(UNMERGE_TARGET_ID).shouldNotBeNull()
                finalTarget.aliases.contains(ALIAS_NAME) shouldBe false

                val newContributor = serverContributorRepository.findById(newId).shouldNotBeNull()
                newContributor.name shouldBe ALIAS_NAME

                // creditedAs clearing invariant — the highest-value assertion.
                // After unmerge, the alias-credited books link to the new contributor with
                // creditedAs = NULL (the canonical name IS the alias; no override needed).
                val finalBookOne = serverBookRepository.findById(BookId(BACHMAN_BOOK_ONE_ID)).shouldNotBeNull()
                val finalBookTwo = serverBookRepository.findById(BookId(BACHMAN_BOOK_TWO_ID)).shouldNotBeNull()
                finalBookOne.contributors
                    .first { it.id == newId }
                    .creditedAs
                    .shouldBeNull()
                finalBookTwo.contributors
                    .first { it.id == newId }
                    .creditedAs
                    .shouldBeNull()

                // Plain King book stays with the target, creditedAs remains null.
                val finalKingBook = serverBookRepository.findById(BookId(KING_BOOK_ID)).shouldNotBeNull()
                finalKingBook.contributors
                    .first { it.id == UNMERGE_TARGET_ID }
                    .creditedAs
                    .shouldBeNull()
            }
        }
    })

/**
 * True once every signal of a fully-applied unmerge is observable in client Room:
 * the new contributor row exists, the target's alias is gone, both alias-credited
 * books are re-linked to the new contributor, and the plain King book is still
 * linked to the target.
 *
 * Split into two helpers to stay within detekt's 4-clause [ComplexCondition] limit.
 */
private suspend fun unmergeFullyLanded(
    clientDb: ListenUpDatabase,
    newId: String,
): Boolean {
    val contributorsLanded = newContributorAndAliasLanded(clientDb, newId)
    val booksLanded = bachmanBooksAndKingBookLanded(clientDb, newId)
    return contributorsLanded && booksLanded
}

/**
 * True when the new contributor row exists in Room AND the target's
 * [ALIAS_NAME] alias has been removed from Room.
 */
private suspend fun newContributorAndAliasLanded(
    clientDb: ListenUpDatabase,
    newId: String,
): Boolean {
    val newContributorExists = clientDb.contributorDao().getById(newId) != null
    val aliasGone = ALIAS_NAME !in clientDb.contributorAliasDao().getForContributor(UNMERGE_TARGET_ID)
    return newContributorExists && aliasGone
}

/**
 * True when both alias-credited books are re-linked to the new contributor AND
 * the plain King book still links to the target.
 */
private suspend fun bachmanBooksAndKingBookLanded(
    clientDb: ListenUpDatabase,
    newId: String,
): Boolean {
    val bachmanBooksRelinked =
        listOf(BACHMAN_BOOK_ONE_ID, BACHMAN_BOOK_TWO_ID).all { bookId ->
            clientDb.contributorDao().getByBookId(bookId).any { it.id.value == newId }
        }
    val kingBookUnchanged =
        clientDb.contributorDao().getByBookId(KING_BOOK_ID).any { it.id.value == UNMERGE_TARGET_ID }
    return bachmanBooksRelinked && kingBookUnchanged
}

private fun bookFixture(
    id: String,
    title: String,
    contributors: List<BookContributorPayload>,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1L,
        contributors = contributors,
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

/**
 * Re-fire a single RPC [call] until it returns [AppResult.Success], up to
 * [COLD_RPC_HANDSHAKE_ATTEMPTS] times. Guards the cold kotlinx.rpc WebSocket handshake, which can
 * transiently 401 under CI contention *before* the request reaches the service — leaving no
 * server-side state, so re-firing is safe. A genuine service failure returns [AppResult.Failure] on
 * every attempt and is returned unchanged, so this never masks a real error.
 */
private suspend fun <T> retryOnColdRpcHandshake(call: suspend () -> AppResult<T>): AppResult<T> {
    var result = call()
    repeat(COLD_RPC_HANDSHAKE_ATTEMPTS - 1) {
        if (result is AppResult.Success) return result
        delay(COLD_RPC_HANDSHAKE_RETRY_DELAY_MS)
        result = call()
    }
    return result
}
