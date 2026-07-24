package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Pins what the client FTS5 tokenizer can and cannot match, so a tokenizer change shows up as a
 * test diff rather than as a user noticing their search got worse.
 *
 * These are characterization tests: they assert *current* behaviour, whatever it is. Their job is
 * to make a deliberate trade visible. The client indexes with `porter` (English stemming) today;
 * the server's `book_search` uses `unicode61 remove_diacritics 2`. Neither supports substring
 * matching, which is what `trigram` buys and what an audiobook library wants most — search intent
 * is dominated by titles, authors and narrators, where stemming does nothing and partial recall
 * ("undat" → *Foundation*) does a great deal.
 *
 * Each test names which tokenizer it expects to satisfy it, so the pair reads as a ledger of the
 * trade rather than a set of assertions someone might "fix" in the wrong direction.
 */
class SearchTokenizerCharacterizationTest :
    FunSpec({

        suspend fun seedIndexedBook(
            db: ListenUpDatabase,
            id: String,
            title: String,
            author: String? = null,
            description: String? = null,
        ) {
            db.bookDao().upsert(
                BookEntity(
                    id = BookId(id),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = title,
                    sortTitle = title,
                    subtitle = null,
                    coverHash = null,
                    totalDuration = 0L,
                    description = description,
                    publishYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                    abridged = false,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
            db.searchDao().insertBookFts(
                bookId = id,
                title = title,
                subtitle = null,
                description = description,
                author = author,
                narrator = null,
                seriesName = null,
                genres = null,
            )
        }

        // ---- The capability we are buying -------------------------------------------------

        test("substring inside a title matches — the reason to adopt trigram") {
            val db = createInMemoryTestDatabase()
            seedIndexedBook(db, id = "b1", title = "Foundation", author = "Isaac Asimov")

            val hits = db.searchDao().searchBooks(query = "undat")

            hits.map { it.book.id.value } shouldBe listOf("b1")
        }

        test("substring in the middle of a word matches — not merely a whole-token hit") {
            val db = createInMemoryTestDatabase()
            seedIndexedBook(db, id = "b2", title = "The Left Hand of Darkness", author = "Ursula K. Le Guin")

            // "arkne" sits INSIDE "Darkness" and is not a token under any word tokenizer. An
            // earlier draft of this test used "guin", which porter already satisfies because
            // "Le Guin" tokenizes to `le` + `guin` — it passed without proving anything about
            // substring matching. Mid-word is the only honest probe.
            val hits = db.searchDao().searchBooks(query = "arkne")

            hits.map { it.book.id.value } shouldBe listOf("b2")
        }

        // ---- The capability we are trading away -------------------------------------------

        test("English stemming does NOT survive the move to trigram — accepted loss") {
            val db = createInMemoryTestDatabase()
            seedIndexedBook(db, id = "b3", title = "The Great Libraries")

            // "library" is NOT a substring of "Libraries" — the only route from one to the other
            // is a linguistic stem, which `porter` has and `trigram` does not. Two earlier drafts
            // of this test were vacuous: one indexed a description containing "runs" verbatim, so
            // the query matched as a plain substring and proved nothing about stemming at all.
            // A stemming probe must use a query the indexed text does not literally contain.
            //
            // Accepted trade: stemming pays only over description prose, our weakest signal,
            // while substring recall pays over titles and names, which is what people search.
            val hits = db.searchDao().searchBooks(query = "library")

            hits.shouldBeEmpty()
        }

        test("queries shorter than three characters find nothing under trigram — accepted limit") {
            val db = createInMemoryTestDatabase()
            seedIndexedBook(db, id = "b4", title = "It", author = "Stephen King")

            // trigram cannot index a token shorter than its window. A two-character query is
            // unsatisfiable by construction, so the UI must not present it as "no results" —
            // it is "keep typing". Pinned here so that requirement cannot be forgotten.
            val hits = db.searchDao().searchBooks(query = "it")

            hits.shouldBeEmpty()
        }
    })
