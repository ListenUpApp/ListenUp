package com.calypsan.listenup.server

import com.calypsan.listenup.core.appCoroutineExceptionHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The server's background scope ([applicationScopeOf]) survives a failure in one of its jobs, and the failure is
 * handled rather than left to the runtime.
 *
 * An exception that escapes a `launch` on a scope with no [CoroutineExceptionHandler] goes to the runtime's last
 * resort: on Kotlin/Native — the shipped server — that terminates the process; on the JVM it lands in the thread's
 * uncaught-exception handler. The scope here runs on a thread whose uncaught-exception handler records, so "the
 * failure reached the runtime" is observable on the JVM runner.
 */
class ApplicationScopeSurvivesFailureTest :
    FunSpec({
        test("the background scope installs the app-lifetime exception handler") {
            val scope = applicationScopeOf(kotlin.coroutines.EmptyCoroutineContext)
            try {
                scope.coroutineContext[CoroutineExceptionHandler] shouldBe appCoroutineExceptionHandler
            } finally {
                scope.cancel()
            }
        }

        test("a failed background job is handled, not left to the runtime, and its siblings still run") {
            val escapedToRuntime = Collections.synchronizedList(mutableListOf<Throwable>())
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "application-scope-test").apply {
                        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escapedToRuntime += e }
                    }
                }
            val dispatcher = executor.asCoroutineDispatcher()
            val scope = applicationScopeOf(dispatcher)
            try {
                val failed = scope.launch { error("boom") }
                failed.join()

                val siblingRan = CompletableDeferred<Unit>()
                scope.launch { siblingRan.complete(Unit) }
                withTimeout(5_000) { siblingRan.await() }

                scope.isActive shouldBe true
                escapedToRuntime.shouldBeEmpty()
            } finally {
                scope.cancel()
                dispatcher.close()
            }
        }
    })
