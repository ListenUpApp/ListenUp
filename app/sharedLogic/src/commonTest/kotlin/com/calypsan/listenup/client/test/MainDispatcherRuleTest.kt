package com.calypsan.listenup.client.test

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Verifies [MainDispatcherRule] installs its [TestDispatcher] as [Dispatchers.Main] during a test,
 * so ViewModel code that dispatches to Main runs under virtual time.
 *
 * Without the rule, `withContext(Dispatchers.Main)` throws `IllegalStateException` in commonTest
 * because no Main dispatcher is installed on any non-Android target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRuleTest :
    FunSpec({
        val mainRule = MainDispatcherRule()

        beforeTest { mainRule.setUp() }

        afterTest { mainRule.tearDown() }

        test("mainDispatcherIsUsableAfterSetUp") {
            runTest(mainRule.testDispatcher) {
                var ran = false
                withContext(Dispatchers.Main) { ran = true }
                withClue("withContext(Dispatchers.Main) must execute under MainDispatcherRule") {
                    ran shouldBe true
                }
            }
        }
    })
