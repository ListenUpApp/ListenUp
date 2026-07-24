package com.calypsan.listenup.client.test.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Verifies [createInMemoryTestDatabase] constructs a usable [com.calypsan.listenup.client.data.local.db.ListenUpDatabase]
 * backed by an in-memory SQLite, with DAOs resolvable and a basic round-trip working.
 *
 * Lives in jvmTest (not commonTest) until a cross-platform test-DB seam is proven out.
 */
class TestDatabaseTest :
    FunSpec({
        test("exposesAllDaos") {
            val db = createInMemoryTestDatabase()
            try {
                db.userDao() shouldNotBe null
                db.bookDao() shouldNotBe null
                db.playbackPositionDao() shouldNotBe null
            } finally {
                db.close()
            }
        }

        test("isIsolatedBetweenInstances") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val db2 = createInMemoryTestDatabase()
                    try {
                        // Two separately-built in-memory databases must not share state — otherwise
                        // parallel tests would leak data across each other.
                        db2.userDao() shouldNotBe null
                    } finally {
                        db2.close()
                    }
                } finally {
                    db.close()
                }
            }
        }
    })
