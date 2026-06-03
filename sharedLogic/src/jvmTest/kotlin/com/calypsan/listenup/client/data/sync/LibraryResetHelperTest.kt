package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * `clearLibraryData` is the sign-out / server-switch reset. It wipes the library
 * rows, but the per-domain sync cursors are part of the same lifecycle: if the
 * rows are gone but the cursors survive, the next login's catch-up resumes from
 * the stale high-water cursor (`?since=<oldCursor>`, server filters
 * `revision > cursor` strictly) and returns nothing for an unchanged library —
 * leaving the freshly-wiped tables permanently empty with no manual recovery.
 *
 * This pins the invariant: **resetting the library also resets its sync cursors**,
 * so catch-up re-pulls the whole library from scratch.
 */
class LibraryResetHelperTest :
    FunSpec({

        test("clearLibraryData also clears the per-domain sync cursors") {
            val db = createInMemoryTestDatabase()
            try {
                // Seed cursors as a synced client would hold them after catch-up.
                db.syncCursorDao().setCursor(SyncCursorEntity(domainName = "books", revision = 4200))
                db.syncCursorDao().setCursor(SyncCursorEntity(domainName = "contributors", revision = 87))

                val helper =
                    LibraryResetHelper(
                        database = db,
                        transactionRunner = RoomTransactionRunner(db),
                        librarySyncContract = mock<LibrarySync>(),
                    )

                helper.clearLibraryData(discardPendingOperations = true)

                // After a reset, no cursor may survive — otherwise catch-up never
                // re-pulls the wiped library on the next login.
                db.syncCursorDao().all().shouldBeEmpty()
            } finally {
                db.close()
            }
        }
    })
