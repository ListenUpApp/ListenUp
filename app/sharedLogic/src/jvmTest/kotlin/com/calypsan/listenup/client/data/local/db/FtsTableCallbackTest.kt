package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Guards [FtsTableCallback] — the Room callback that creates the FTS5 search tables on every
 * platform. It regressed on Apple platforms (the callback was wired into the Android and JVM
 * database builders but not the Apple one), so every search and FTS rebuild on iOS threw
 * `no such table: books_fts`, storming the sync/reconcile loop. This pins the contract that
 * opening a database with the callback makes all three `*_fts` tables queryable.
 */
class FtsTableCallbackTest :
    FunSpec({

        test("opening a database with FtsTableCallback creates queryable books/contributors/series FTS tables") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val searchDao = db.searchDao()

                    // Each call touches a different `*_fts` table. Before the fix these threw
                    // `no such table: <name>_fts`; now they resolve against the created tables.
                    searchDao.countBooksFts() shouldBe 0
                    searchDao.clearContributorsFts()
                    searchDao.clearSeriesFts()

                    // A MATCH query against the freshly-created (empty) index returns no rows
                    // rather than throwing — the path that fails for a missing virtual table.
                    searchDao.searchBooks(query = "anything", limit = 10) shouldNotBe null
                } finally {
                    db.close()
                }
            }
        }
    })
