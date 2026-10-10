package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeTwoTabRefresh
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * Two tabs on one origin share one `localStorage`, so for credentials they are two client graphs
 * over one store. The server rotates a refresh token on every use; this proves that interleaved
 * refreshes from both tabs leave both signed in, rather than one tab presenting a token the other
 * has already replaced.
 *
 * Needs a booted server, so it is gated exactly like [AuthArcTest]. It sorts after that spec and
 * signs in as the admin it created; it signs in rather than sets up when the server has users.
 */
class TwoTabSessionTest :
    FunSpec({
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test("two tabs refreshing in turn both stay signed in")
            .config(enabled = serverBooted) {
                val suffix = Random.nextInt(0, Int.MAX_VALUE)
                val probe =
                    probeTwoTabRefresh(
                        workerA = createSqliteWorker(),
                        workerB = createSqliteWorker(),
                        dbNameA = "two-tab-a-$suffix",
                        dbNameB = "two-tab-b-$suffix",
                        email = "probe-admin@example.invalid",
                        password = "probe-admin-password-1",
                    )

                // The harness forwards only TeamCity messages from the console, so the whole probe
                // goes in the clue: one failure should say which refresh broke and what each tab
                // ended up as.
                withClue("probe = $probe") {
                    assertSoftly {
                        probe.failure shouldBe null
                        probe.bothSignedIn shouldBe true
                        probe.refreshOutcomes shouldBe listOf("ok", "ok", "ok", "ok")
                        probe.finalStateOfA shouldBe "Authenticated"
                        probe.finalStateOfB shouldBe "Authenticated"
                        probe.authedCallOnASucceeded shouldBe true
                        probe.authedCallOnBSucceeded shouldBe true
                    }
                }
            }
    })
