package com.calypsan.listenup.client.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * KMP-compatible composition over [Dispatchers.setMain] / [Dispatchers.resetMain].
 *
 * commonTest has no JUnit4 `@Rule` mechanism, so this is driven via explicit
 * `@BeforeTest` / `@AfterTest` delegation — the same pattern `MainCoroutineRule` uses
 * on the JVM, minus the `TestWatcher` inheritance.
 *
 * ```
 * class SomeViewModelTest {
 *     private val mainRule = MainDispatcherRule()
 *
 *     @BeforeTest fun beforeTest() = mainRule.setUp()
 *     @AfterTest  fun afterTest()  = mainRule.tearDown()
 *
 *     @Test fun someBehaviour() = runTest(mainRule.testDispatcher) { ... }
 * }
 * ```
 *
 * Replaces the identical `Dispatchers.setMain` / `Dispatchers.resetMain` pair that currently
 * appears in every VM test file — see Finding 12 D8.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) {
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    fun tearDown() {
        Dispatchers.resetMain()
    }
}
