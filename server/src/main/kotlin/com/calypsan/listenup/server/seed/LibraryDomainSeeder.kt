package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.LibraryTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/**
 * Seeds the demo library — a "Demo Library" pointing at the pre-generated
 * synthetic audiobook library (produced by `:server:generateSeedLibrary`).
 *
 * Writes through [LibraryAdminService.createLibrary] — the real domain
 * write-path — so the seeded library is indistinguishable from one created
 * via the REST surface. The scanner will pick up the pre-generated files once
 * a scan is triggered.
 *
 * Idempotency: [isAlreadySeeded] returns `true` when any library row exists
 * in the `libraries` table (the demo profile is designed for a single library).
 * A failure response from [LibraryAdminService.createLibrary] is logged and
 * swallowed so a second `seed()` call never throws.
 *
 * Runs at order 5 — after [UserDomainSeeder] (order 0) and before the
 * scan-dependent [PlaybackPositionDomainSeeder] (order 10) and
 * [ListeningEventDomainSeeder] (order 20).
 */
internal class LibraryDomainSeeder(
    private val db: Database,
    private val libraryAdminService: LibraryAdminService,
    private val demoLibraryPath: String,
) : DomainSeeder {
    override val domainName: String = "library"

    /** Runs after [UserDomainSeeder] (order 0), before the scan-dependent seeders. */
    override val order: Int = 5

    /** Returns true when at least one non-deleted library row already exists. */
    override suspend fun isAlreadySeeded(): Boolean =
        suspendTransaction(db) {
            LibraryTable
                .selectAll()
                .where { LibraryTable.deletedAt.isNull() }
                .limit(1)
                .any()
        }

    override suspend fun seed() {
        when (
            val result =
                libraryAdminService.createLibrary(
                    CreateLibraryRequest(
                        name = DEMO_LIBRARY_NAME,
                        folderPaths = listOf(demoLibraryPath),
                    ),
                )
        ) {
            is AppResult.Success ->
                logger.info { "seed [$domainName]: Demo Library created id=${result.data.id.value}" }

            is AppResult.Failure ->
                logger.warn { "seed [$domainName]: createLibrary returned ${result.error.code} — skipping" }
        }
    }

    companion object {
        const val DEMO_LIBRARY_NAME = "Demo Library"
    }
}
