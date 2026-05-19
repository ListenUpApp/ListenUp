package com.calypsan.listenup.client.test.di

import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Verifies [KoinTestRule] starts Koin with the supplied modules and stops it on tearDown,
 * establishing the KMP-compat alternative to JVM-only `org.koin.test.KoinTestRule`.
 */
class KoinTestRuleTest {
    private val fakeModule =
        module {
            single<Greeter> { HelloGreeter() }
        }

    private val koinRule = KoinTestRule(listOf(fakeModule))

    @BeforeTest
    fun beforeTest() = koinRule.setUp()

    @AfterTest
    fun afterTest() = koinRule.tearDown()

    @Test
    fun startsKoinWithProvidedModules() {
        val greeter = KoinPlatform.getKoin().get<Greeter>()
        assertNotNull(greeter)
        assertEquals("hello", greeter.greet())
    }

    @Test
    fun stopsKoinAfterTeardown() {
        // Simulate a completed test lifecycle: tearDown then attempt a resolve.
        koinRule.tearDown()
        assertFailsWith<IllegalStateException> { KoinPlatform.getKoin() }
        // Restart so @AfterTest tearDown doesn't explode on an already-stopped Koin.
        koinRule.setUp()
    }

    private interface Greeter {
        fun greet(): String
    }

    private class HelloGreeter : Greeter {
        override fun greet(): String = "hello"
    }
}
