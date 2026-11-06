package com.calypsan.listenup.client.design.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A pull spinner stays up while its refresh is suspended, and comes down when it returns. */
@RunWith(RobolectricTestRunner::class)
class PullRefreshGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun hostGate(): PullRefreshGate {
        lateinit var gate: PullRefreshGate
        composeRule.setContent { gate = rememberPullRefreshGate() }
        composeRule.waitForIdle()
        return gate
    }

    @Test
    fun `isPulling is true while the refresh is suspended and false once it returns`() {
        val gate = hostGate()
        val refresh = CompletableDeferred<Unit>()
        gate.isPulling shouldBe false

        composeRule.runOnIdle { gate.pull { refresh.await() } }
        composeRule.waitForIdle()
        gate.isPulling shouldBe true

        refresh.complete(Unit)
        composeRule.waitForIdle()
        gate.isPulling shouldBe false
    }
}
