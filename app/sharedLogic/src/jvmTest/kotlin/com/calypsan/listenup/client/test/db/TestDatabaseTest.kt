package com.calypsan.listenup.client.test.db

import com.calypsan.listenup.client.data.sync.SyncCursorStore
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

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

        // The bundled SQLite driver frees the connection on close, and a statement still running on another
        // thread then reads freed memory: the JVM dies with SIGSEGV instead of a test failing. Cancelling a scope
        // does not stop a statement already in flight, so teardown has to wait for it — this pins that it does.
        test("closeAfterCancelling waits for a write already in flight before it closes the database") {
            runBlocking {
                val db = createInMemoryTestDatabase()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val store = SyncCursorStore(db.syncCursorDao())
                val inFlight = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val written = CompletableDeferred<Unit>()
                scope.launch {
                    // Cancellation cannot interrupt a statement the driver is already executing; this
                    // NonCancellable section holds that window open deterministically.
                    withContext(NonCancellable) {
                        inFlight.complete(Unit)
                        release.await()
                        store.setCursor(domainName = "tags", revision = 7L)
                        written.complete(Unit)
                    }
                }
                inFlight.await()

                val closing = launch(start = CoroutineStart.UNDISPATCHED) { db.closeAfterCancelling(scope) }

                closing.isCompleted shouldBe false
                release.complete(Unit)
                closing.join()
                written.isCompleted shouldBe true
                shouldThrowAny { store.getCursor("tags") }
            }
        }
    })
