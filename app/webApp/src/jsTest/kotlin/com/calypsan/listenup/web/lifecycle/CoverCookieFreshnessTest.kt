package com.calypsan.listenup.web.lifecycle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.events.Event
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

private const val WAIT_MS = 2_000L
private const val POLL_MS = 10L

private suspend fun awaitCalls(
    count: () -> Int,
    atLeast: Int,
) {
    withTimeout(WAIT_MS) { while (count() < atLeast) delay(POLL_MS) }
}

/**
 * The browser's covers authenticate with a cookie the DOM sends on its own, so no request of ours
 * ever notices the token behind it expiring. These pin the rule that keeps it fresh: only while the
 * tab is visible — foreground, where a refresh can't be frozen mid-flight — and at once on return.
 */
class CoverCookieFreshnessTest :
    FunSpec({

        test("a visible tab keeps the cover cookie fresh on its own clock") {
            var checks = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                keepCoverCookieFreshWhileVisible(
                    ensureFresh = { checks++ },
                    isVisible = { true },
                    scope = scope,
                    checkEvery = 20.milliseconds,
                )
            try {
                awaitCalls({ checks }, atLeast = 3)
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a hidden tab never refreshes, and coming back refreshes at once") {
            var checks = 0
            var visible = false
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                keepCoverCookieFreshWhileVisible(
                    ensureFresh = { checks++ },
                    isVisible = { visible },
                    scope = scope,
                    checkEvery = 20.milliseconds,
                )
            try {
                delay(200)
                checks shouldBe 0

                visible = true
                document.dispatchEvent(Event("visibilitychange"))
                awaitCalls({ checks }, atLeast = 1)
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("returning to the tab refreshes without waiting for the clock") {
            var checks = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                keepCoverCookieFreshWhileVisible(
                    ensureFresh = { checks++ },
                    isVisible = { true },
                    scope = scope,
                    checkEvery = 1.hours,
                )
            try {
                document.dispatchEvent(Event("visibilitychange"))
                awaitCalls({ checks }, atLeast = 1)
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a failed refresh keeps the watch running") {
            var checks = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                keepCoverCookieFreshWhileVisible(
                    ensureFresh = {
                        checks++
                        error("refresh blew up")
                    },
                    isVisible = { true },
                    scope = scope,
                    checkEvery = 20.milliseconds,
                )
            try {
                awaitCalls({ checks }, atLeast = 2)
            } finally {
                dispose()
                scope.cancel()
            }
        }
    })
