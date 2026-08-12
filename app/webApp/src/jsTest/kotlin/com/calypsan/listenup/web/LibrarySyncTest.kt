package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeLibrarySync
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * THE end-to-end sync proof: an authenticated browser ends up with books in its own Room store.
 *
 * Requires a booted server (`webAuthKotest`) — there is nothing to sync from otherwise, and that
 * lane's harness seeds the server's library so there is something to count. Both browser lanes
 * compile one spec bundle, so the server-free lane skips this by config rather than by absence,
 * exactly as `AuthArcTest` does.
 */
class LibrarySyncTest :
    FunSpec({
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test("an authenticated browser syncs books into its local store")
            .config(enabled = serverBooted) {
                val probe =
                    probeLibrarySync(
                        worker = createSqliteWorker(),
                        dbName = "library-sync-probe-${Random.nextInt(0, Int.MAX_VALUE)}",
                        email = "probe-admin@example.invalid",
                        password = "probe-admin-password-1",
                    )

                // The whole probe goes in the clue for the same reason AuthArcTest does this: the
                // browser console is not forwarded by the harness, only TeamCity messages are, so
                // `expected:<true> but was:<false>` alone would say nothing about which stage broke.
                withClue("probe = $probe") {
                    assertSoftly {
                        probe.failure shouldBe null
                        probe.reachedAuthenticated shouldBe true
                        probe.connectSucceeded shouldBe true
                        // The count, not merely "sync ran": a started engine that writes no rows
                        // is precisely the failure this spec exists to catch.
                        probe.localBookCount shouldBeGreaterThan 0
                    }
                }
            }
    })
