package com.calypsan.listenup.core

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Guards the iOS crash-hardening contract: an uncaught exception in a fire-and-forget `launch` on a
 * long-lived scope must be absorbed (logged), not allowed to reach `propagateExceptionFinalResort`,
 * which terminates the process on Kotlin/Native. See [appCoroutineExceptionHandler].
 */
class AppCoroutineExceptionHandlerTest :
    FunSpec({
        test("the handler absorbs an exception instead of rethrowing") {
            shouldNotThrowAny {
                appCoroutineExceptionHandler.handleException(
                    EmptyCoroutineContext,
                    RuntimeException("simulated realtime-socket drop"),
                )
            }
        }

        test("a scope built the app way keeps the handler in context and survives a throwing child") {
            // Mirrors how appCoreModule / iosPlaybackModule build their long-lived scopes.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + appCoroutineExceptionHandler)
            try {
                scope.coroutineContext[CoroutineExceptionHandler] shouldBe appCoroutineExceptionHandler

                val siblingRan = CompletableDeferred<Unit>()
                // Without the handler this reaches propagateExceptionFinalResort and kills the
                // process on Kotlin/Native.
                scope.launch { throw RuntimeException("simulated realtime-socket drop") }
                // A sibling on the same scope must still run to completion.
                scope.launch { siblingRan.complete(Unit) }

                withTimeout(5.seconds) { siblingRan.await() }
                scope.isActive shouldBe true
            } finally {
                scope.cancel()
            }
        }
    })
