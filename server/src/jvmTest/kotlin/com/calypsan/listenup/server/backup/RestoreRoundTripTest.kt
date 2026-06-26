package com.calypsan.listenup.server.backup

import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Pins that a restore's db-file swap reaches the repositories: after `closePool → move the replacement
 * file over the live path → reopenPool`, a [ListenUpDatabase] built on `handle.sqlDriver` must observe
 * the swapped-in data.
 *
 * It holds because [com.calypsan.listenup.server.db.sqldelight.DriverFactory]'s `JdbcSqliteDriver` opens
 * a fresh connection per operation (the same property behind the one-time-PRAGMA bug), so the next query
 * after the rename reads the new file at the path — the repos' driver never pins the old inode. This is a
 * regression guard for that requirement across the Hikari removal that reworks
 * [com.calypsan.listenup.server.db.DatabaseHandle.closePool]/`reopenPool`.
 */
class RestoreRoundTripTest :
    FunSpec({
        test("after closePool + db-file swap + reopenPool, repos on handle.sqlDriver see the swapped-in data") {
            val liveFile = Path(SystemTemporaryDirectory, "listenup-live-${System.nanoTime()}.db")
            val replacementFile = Path(SystemTemporaryDirectory, "listenup-replacement-${System.nanoTime()}.db")

            val liveHandle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$liveFile"))
            val replacementHandle =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$replacementFile"))

            // Seed a library row into the REPLACEMENT db, then close it so its file is consistent on disk.
            ListenUpDatabase(replacementHandle.sqlDriver).seedLibrary("from-replacement")
            replacementHandle.close()

            // The live db (no such row) is what repos currently read.
            val repos = ListenUpDatabase(liveHandle.sqlDriver)
            repos.librariesQueries.selectById("from-replacement").executeAsOneOrNull() shouldBe null

            // Restore: close live handles, move the replacement file over the live path (rename = new
            // inode, matching RestoreOrchestrator.swapFile), drop stale WAL sidecars, reopen.
            liveHandle.closePool()
            SystemFileSystem.atomicMove(replacementFile, liveFile)
            SystemFileSystem.delete(Path(liveFile.parent!!, "${liveFile.name}-wal"), mustExist = false)
            SystemFileSystem.delete(Path(liveFile.parent!!, "${liveFile.name}-shm"), mustExist = false)
            liveHandle.reopenPool()

            // The SAME repos instance (bound to handle.sqlDriver) must now see the swapped-in row.
            repos.librariesQueries
                .selectById("from-replacement")
                .executeAsOneOrNull()
                ?.id shouldBe "from-replacement"

            liveHandle.close()
        }
    })

/** Minimal library-row seed via the generated queries (DDL-defaulted columns supplied explicitly). */
private fun ListenUpDatabase.seedLibrary(id: String) {
    val now = System.currentTimeMillis()
    librariesQueries.insert(
        id = id,
        name = "L",
        metadata_precedence = "embedded,abs,sidecar",
        access_mode = "shared",
        created_by_user_id = null,
        created_at = now,
        revision = 0L,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
}
