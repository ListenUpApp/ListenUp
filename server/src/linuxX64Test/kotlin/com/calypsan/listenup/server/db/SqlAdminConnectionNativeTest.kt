package com.calypsan.listenup.server.db

import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test

/**
 * Native parity proof for the SQLite admin-connection seam ([openAdminConnection] → libsqlite3 cinterop).
 * Exercises the real native path used by migrations / VACUUM / external-DB reads: open a read-write
 * connection on a fresh file, run DDL + DML via [SqlAdminConnection.execute], read it back via the bound
 * [SqlAdminConnection.query], and assert the round-trip survives the cinterop marshalling.
 */
class SqlAdminConnectionNativeTest {
    @Test
    fun executesDdlAndQueriesItBackOverTheNativeDriver() {
        // Temp dir is unusable in the linuxX64 test runner — use a working-directory-relative file.
        val dbName = "lu-admin-native-test-${Random.nextInt(1, Int.MAX_VALUE).toString(HEX_RADIX)}.db"
        try {
            openAdminConnection(dbName, readOnly = false).use { conn ->
                conn.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
                conn.execute("INSERT INTO t (id, name) VALUES (1, 'kaladin')")
                conn.execute("INSERT INTO t (id, name) VALUES (2, 'shallan')")
                val names =
                    conn.query(
                        sql = "SELECT name FROM t WHERE name = ?",
                        bind = { bindString(1, "shallan") },
                        map = { row -> row.getString("name") },
                    )
                names shouldBe listOf("shallan")
            }
        } finally {
            // journal_mode=WAL (set for read-write) leaves -wal / -shm sidecars; remove all three.
            for (suffix in listOf("", "-wal", "-shm")) {
                SystemFileSystem.delete(Path(dbName + suffix), mustExist = false)
            }
        }
    }

    private companion object {
        const val HEX_RADIX = 16
    }
}
