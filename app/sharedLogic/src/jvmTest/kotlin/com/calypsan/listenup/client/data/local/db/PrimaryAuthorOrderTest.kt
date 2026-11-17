package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Every surface that names one author for a book names the same one: the first in credit order, as credited.
 *
 * The case is Empire of the Vampire, credited "Jay Kristoff, Bon Orthwick". Its junction rows are stored with the
 * illustrator first, so storage order, contributor id and name all point at the wrong author; only the credit
 * ordinal names Jay Kristoff. Discover once named Bon Orthwick here while Book Detail named Jay Kristoff.
 */
class PrimaryAuthorOrderTest :
    FunSpec({
        test("Recently Added names the first credited author, not the first row stored") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedEmpireOfTheVampire(db)
                    db.bookDao().observeRecentlyAddedWithAuthor(limit = 10).test {
                        awaitItem().single().authorName shouldBe "Jay Kristoff"
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("the Activity feed names the same author as Recently Added") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedEmpireOfTheVampire(db)
                    db.activityDao().upsert(listeningSession(bookId = EMPIRE))
                    db.activityDao().observeRecent(limit = 10).test {
                        awaitItem().single().bookAuthorName shouldBe "Jay Kristoff"
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("a credited name wins over the contributor's own name, as on Book Detail") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedEmpireOfTheVampire(db, jayCreditedAs = "J. Kristoff")
                    db.bookDao().observeRecentlyAddedWithAuthor(limit = 10).test {
                        awaitItem().single().authorName shouldBe "J. Kristoff"
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("Book Detail lists authors in credit order, whatever order the rows were stored in") {
            val refs =
                listOf(
                    BookContributorCrossRef(BookId(EMPIRE), ContributorId(BON), ROLE_AUTHOR, ordinal = 1),
                    BookContributorCrossRef(BookId(EMPIRE), ContributorId(JAY), ROLE_AUTHOR, ordinal = 0),
                )
            val byId = listOf(contributor(BON, "Bon Orthwick"), contributor(JAY, "Jay Kristoff")).associateBy { it.id }
            refs.extractByRole(ROLE_AUTHOR, byId).map { it.name } shouldBe listOf("Jay Kristoff", "Bon Orthwick")
        }
    })

private const val EMPIRE = "empire-of-the-vampire"

// Ids chosen so the wrong author sorts first by id as well as by storage order.
private const val BON = "a-bon-orthwick"
private const val JAY = "z-jay-kristoff"

private suspend fun seedEmpireOfTheVampire(
    db: ListenUpDatabase,
    jayCreditedAs: String? = null,
) {
    db.bookDao().upsert(
        BookEntity(
            id = BookId(EMPIRE),
            libraryId = LibraryId("library"),
            folderId = FolderId("folder"),
            title = "Empire of the Vampire",
            sortTitle = "Empire of the Vampire",
            subtitle = null,
            coverHash = null,
            totalDuration = 0L,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            createdAt = Timestamp(1_000L),
            updatedAt = Timestamp(1_000L),
        ),
    )
    db.contributorDao().upsert(contributor(BON, "Bon Orthwick"))
    db.contributorDao().upsert(contributor(JAY, "Jay Kristoff"))
    // Stored illustrator-first; the ordinal carries the credit order.
    db.bookContributorDao().insert(
        BookContributorCrossRef(BookId(EMPIRE), ContributorId(BON), ROLE_AUTHOR, ordinal = 1),
    )
    db.bookContributorDao().insert(
        BookContributorCrossRef(BookId(EMPIRE), ContributorId(JAY), ROLE_AUTHOR, creditedAs = jayCreditedAs, ordinal = 0),
    )
}

private fun contributor(
    id: String,
    name: String,
) = ContributorEntity(
    id = ContributorId(id),
    name = name,
    description = null,
    imagePath = null,
    createdAt = Timestamp(1L),
    updatedAt = Timestamp(1L),
)

private fun listeningSession(bookId: String) =
    ActivityEntity(
        id = "activity-1",
        userId = "user-1",
        type = "listening_session",
        occurredAt = 2_000L,
        bookId = bookId,
        isReread = false,
        durationMs = 60_000L,
        milestoneValue = 0,
        milestoneUnit = null,
        shelfId = null,
        shelfName = null,
    )
