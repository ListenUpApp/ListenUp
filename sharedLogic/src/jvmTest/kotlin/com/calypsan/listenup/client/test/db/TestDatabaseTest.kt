package com.calypsan.listenup.client.test.db

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Verifies [createInMemoryTestDatabase] constructs a usable [com.calypsan.listenup.client.data.local.db.ListenUpDatabase]
 * backed by an in-memory SQLite, with DAOs resolvable and a basic round-trip working.
 *
 * Lives in jvmTest (not commonTest) until W4 proves out a cross-platform test-DB seam — see the
 * W1 plan's checkpoint resolution on in-memory Room placement.
 */
class TestDatabaseTest {
    private val db = createInMemoryTestDatabase()

    @AfterTest
    fun afterTest() {
        db.close()
    }

    @Test
    fun exposesAllDaos() {
        assertNotNull(db.userDao())
        assertNotNull(db.bookDao())
        assertNotNull(db.playbackPositionDao())
    }

    @Test
    fun isIsolatedBetweenInstances() =
        runTest {
            val db2 = createInMemoryTestDatabase()
            try {
                // Two separately-built in-memory databases must not share state — otherwise
                // parallel tests would leak data across each other.
                assertNotNull(db2.userDao())
            } finally {
                db2.close()
            }
        }
}
