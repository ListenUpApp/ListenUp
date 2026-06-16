package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.LibraryTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/**
 * Seeds the demo library — ensures the singleton library has the pre-generated
 * synthetic audiobook folder registered (produced by `:server:generateSeedLibrary`).
 *
 * Writes through [LibraryAdminService.addFolder] — the real domain
 * write-path — so the seeded folder is indistinguishable from one added via the
 * REST surface. The scanner will pick up the pre-generated files once a scan is
 * triggered.
 *
 * Idempotency: [isAlreadySeeded] returns `true` when any active library row exists
 * (the singleton library is always bootstrapped on first access). [addFolder]
 * silently returns [com.calypsan.listenup.api.error.LibraryError.DuplicateFolder] on
 * a second call with the same path; [seed] swallows that so re-running is safe.
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
        when (val result = libraryAdminService.addFolder(demoLibraryPath)) {
            is AppResult.Success -> {
                logger.info { "seed [$domainName]: Demo folder registered id=${result.data.id.value} path=$demoLibraryPath" }
            }

            is AppResult.Failure -> {
                logger.warn { "seed [$domainName]: addFolder returned ${result.error.code} — skipping" }
            }
        }
    }

    companion object {
        const val DEMO_LIBRARY_NAME = "Demo Library"
    }
}
