package com.calypsan.listenup.client.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies [MainDispatcherRule] installs its [TestDispatcher] as [Dispatchers.Main] during a test,
 * so ViewModel code that dispatches to Main runs under virtual time.
 *
 * Without the rule, `withContext(Dispatchers.Main)` throws `IllegalStateException` in commonTest
 * because no Main dispatcher is installed on any non-Android target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRuleTest {
    private val mainRule = MainDispatcherRule()

    @BeforeTest
    fun beforeTest() = mainRule.setUp()

    @AfterTest
    fun afterTest() = mainRule.tearDown()

    @Test
    fun mainDispatcherIsUsableAfterSetUp() =
        runTest(mainRule.testDispatcher) {
            var ran = false
            withContext(Dispatchers.Main) { ran = true }
            assertTrue(ran, "withContext(Dispatchers.Main) must execute under MainDispatcherRule")
        }
}
