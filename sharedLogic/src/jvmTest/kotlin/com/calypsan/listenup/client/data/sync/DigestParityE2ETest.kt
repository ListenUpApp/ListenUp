package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

/**
 * Cross-stack parity guard: proves [DigestComputer.compute] produces byte-for-byte
 * identical output to the server's [com.calypsan.listenup.server.sync.SyncableRepository.digest]
 * over the same `(id, revision)` rows.
 *
 * Seeds three series on the real server repository (two live, one soft-deleted so
 * the digest must include a tombstoned row), then computes both the server digest
 * and the client digest and asserts count AND hash equality.
 *
 * If this test ever fails it means the two algorithms have drifted — do not weaken
 * the assertion; instead reconcile the implementations.
 */
class DigestParityE2ETest :
    FunSpec({

        test("client DigestComputer produces identical count and hash to server digest") {
            // Stand up a real migrated SQLite database the same way withInMemoryDatabase does.
            val tmp =
                Files.createTempFile("listenup-digest-parity-", ".db").toFile().apply {
                    deleteOnExit()
                }
            val db =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).database

            val repo = SeriesRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())

            runTest {
                // Seed: two live series + one that is immediately soft-deleted (tombstoned).
                repo.resolveOrCreate("Mistborn")
                repo.resolveOrCreate("Stormlight")
                val thirdId = repo.resolveOrCreate("Deleted Series")
                repo.softDelete(thirdId)

                // Server digest over all rows with revision ≤ Long.MAX_VALUE.
                val serverDigest = repo.digest(userId = null, cursor = Long.MAX_VALUE)

                // Retrieve the same (id, revision) pairs the server digest read.
                val rows = repo.allIdRevisionsForTest()

                // Client digest over those identical pairs.
                val clientDigest = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = rows)

                // Both sides must agree on count and hash — this is the wire contract.
                clientDigest.count shouldBe serverDigest.count
                clientDigest.hash shouldBe serverDigest.hash
            }
        }
    })
