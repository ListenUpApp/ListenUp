package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeBrowserStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * THE Room-on-web runtime proof: a real browser, a real web worker running SQLite wasm, the
 * real ListenUp schema, and OPFS persistence beyond a single connection.
 *
 * The FTS assertion is the ListenUp-specific half. Search here has no network path — it
 * reads the local index unconditionally — so a browser client is only viable if FTS5 with
 * the trigram tokenizer works in wasm. The tokenizer is present in the binary; this proves
 * it end to end, through the real `FtsTableCallback` and the real `SearchDao` query.
 */
class RoomWebRuntimeTest :
    FunSpec({
        test("the schema builds, a book round-trips, and trigram FTS matches a substring") {
            // Unique per run: OPFS outlives the test and karma reuses the browser profile.
            val dbName = "listenup-proof-${Random.nextInt(0, Int.MAX_VALUE)}.db"

            val probe = probeBrowserStore(createSqliteWorker(), dbName, seed = true)

            probe.opened shouldBe true
            probe.roundTrippedTitle shouldBe "Foundation"
            probe.ftsMatchCount shouldBe 1
        }

        test("data survives a worker teardown and a fresh connection") {
            val dbName = "listenup-persist-${Random.nextInt(0, Int.MAX_VALUE)}.db"

            val firstWorker = createSqliteWorker()
            probeBrowserStore(firstWorker, dbName, seed = true).roundTrippedTitle shouldBe "Foundation"

            // Terminate so the first connection's OPFS handles are released. A second probe
            // over a FRESH worker, same dbName, must see the same rows purely via OPFS.
            firstWorker.terminate()

            val reread = probeBrowserStore(createSqliteWorker(), dbName, seed = false)
            reread.roundTrippedTitle shouldBe "Foundation"
            reread.ftsMatchCount shouldBe 1
        }
    })
