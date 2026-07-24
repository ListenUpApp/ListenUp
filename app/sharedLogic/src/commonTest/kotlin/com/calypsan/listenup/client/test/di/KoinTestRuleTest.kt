package com.calypsan.listenup.client.test.di

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * Verifies [KoinTestRule] starts Koin with the supplied modules and stops it on tearDown,
 * establishing the KMP-compat alternative to JVM-only `org.koin.test.KoinTestRule`.
 */
class KoinTestRuleTest :
    FunSpec({
        val fakeModule =
            module {
                single<Greeter> { HelloGreeter() }
            }

        val koinRule = KoinTestRule(listOf(fakeModule))

        beforeTest { koinRule.setUp() }

        afterTest { koinRule.tearDown() }

        test("startsKoinWithProvidedModules") {
            val greeter = KoinPlatform.getKoin().get<Greeter>()
            greeter.shouldNotBeNull()
            greeter.greet() shouldBe "hello"
        }

        test("stopsKoinAfterTeardown") {
            // Simulate a completed test lifecycle: tearDown then attempt a resolve.
            koinRule.tearDown()
            shouldThrow<IllegalStateException> { KoinPlatform.getKoin() }
            // Restart so afterTest tearDown doesn't explode on an already-stopped Koin.
            koinRule.setUp()
        }
    })

private interface Greeter {
    fun greet(): String
}

private class HelloGreeter : Greeter {
    override fun greet(): String = "hello"
}
