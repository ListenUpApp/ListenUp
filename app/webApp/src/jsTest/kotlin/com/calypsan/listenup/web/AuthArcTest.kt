package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeAuthArc
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * THE end-to-end auth proof: a browser creates the first admin on a real server through the real
 * shared `SetupViewModel`, reaches `AuthState.Authenticated`, and then makes an **authenticated**
 * RPC call that succeeds.
 *
 * The last clause is the one that matters. Reaching `Authenticated` only proves a state machine
 * ran — it cannot see whether the access token reached the RPC channel's bearer provider, which is
 * the most likely thing to be wrong the first time a browser holds a session.
 *
 * **This spec owns server setup.** The server boots with a fresh `mkdtemp` home and therefore no
 * users, which is what makes `NeedsSetup` reachable without a fixture — but it is a
 * once-per-server-boot transition. No other spec may drive setup: the second one would find the
 * server already configured and fail for a reason that has nothing to do with what it tests.
 *
 * Enabled only when a server was actually booted, for the same reason `RpcTransportTest` is: both
 * browser lanes compile ONE spec bundle, and the server-free `webKotest` lane cannot run this.
 * That is a declared configuration difference rather than an escape hatch — each lane pins its own
 * `KOTEST_MIN_TESTS` floor, so neither can silently drop a spec.
 */
class AuthArcTest :
    FunSpec({
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test("a browser signs up, becomes authenticated, and makes an authed RPC call")
            .config(enabled = serverBooted) {
                val probe =
                    probeAuthArc(
                        worker = createSqliteWorker(),
                        dbName = "auth-arc-probe-${Random.nextInt(0, Int.MAX_VALUE)}",
                        email = "probe-admin@example.invalid",
                        password = "probe-admin-password-1",
                    )

                // The whole probe goes in the clue, and every assertion runs. This test exists to
                // diagnose, not merely to gate: `expected:<true> but was:<false>` on its own says
                // which line broke and nothing about why, and the browser console is not forwarded
                // by the harness — only TeamCity messages are. One failure should tell you whether
                // setup never completed, the token never attached, or the call reached a server
                // that had no users.
                withClue("probe = $probe") {
                    assertSoftly {
                        probe.setupSucceeded shouldBe true
                        probe.reachedAuthenticated shouldBe true
                        // Asserted before the boolean: a typed code tells you whether the token
                        // never attached (an AUTH_* code) or the call itself is wrong.
                        probe.authedCallErrorCode shouldBe null
                        probe.authedCallSucceeded shouldBe true
                        // The admin this probe just created is the server's only user. One is proof
                        // the call reached the server; zero would be indistinguishable from a
                        // local read.
                        probe.userCount shouldBe 1
                    }
                }
            }
    })
