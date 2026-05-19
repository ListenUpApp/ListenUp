package com.calypsan.listenup.client.test.di

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

/**
 * KMP-compatible equivalent of `org.koin.test.KoinTestRule` (JUnit4-only).
 *
 * Starts Koin with [modules] on [setUp] and stops it on [tearDown]. Tests compose this
 * via explicit `@BeforeTest` / `@AfterTest` delegation, same shape as [com.calypsan.listenup.client.test.MainDispatcherRule].
 *
 * ```
 * class SomeRepositoryTest {
 *     private val koinRule = KoinTestRule(listOf(dataModule, fakesModule()))
 *
 *     @BeforeTest fun beforeTest() = koinRule.setUp()
 *     @AfterTest  fun afterTest()  = koinRule.tearDown()
 *
 *     @Test fun resolves() { KoinPlatform.getKoin().get<SomeRepository>() }
 * }
 * ```
 *
 * Source: Koin testing guide — https://insert-koin.io/docs/reference/koin-test/testing.
 * See Finding 12 D5 for the motivation (every VM test manually constructs dependencies).
 */
class KoinTestRule(
    private val modules: List<Module>,
) {
    fun setUp() {
        startKoin { modules(modules) }
    }

    fun tearDown() {
        stopKoin()
    }
}
