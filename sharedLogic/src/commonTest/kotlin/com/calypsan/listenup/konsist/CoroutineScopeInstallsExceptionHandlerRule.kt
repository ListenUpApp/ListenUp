package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the iOS/macOS crash-net: every production `CoroutineScope(...)` must install
 * [com.calypsan.listenup.client.core.appCoroutineExceptionHandler].
 *
 * **Why.** On Kotlin/Native an uncaught exception in a coroutine routes to
 * `propagateExceptionFinalResort`, which **terminates the process** — so on iOS/macOS a single
 * transient failure in a fire-and-forget `launch` on an unguarded scope crashes the whole app (JVM
 * merely logs it). `appCoroutineExceptionHandler` logs the throwable and lets the app keep running.
 * The handler is installed app-wide, but a scope created without it silently reopens the crash
 * class — this rule blocks that regression (its discovery already caught the missed macOS scope).
 *
 * The check is intentionally coarse — file-level text presence — because the scope constructions live
 * inside Koin module lambdas / property initializers, not addressable declarations. Every scope file
 * in `:sharedLogic` holds exactly one construction, so "contains `CoroutineScope(` ⇒ contains
 * `appCoroutineExceptionHandler`" is precise enough. The handler's own definition file references both
 * strings, so it passes without an allowlist.
 *
 * Scoped to `:sharedLogic` — that's where the client's app-lifetime scopes live and where the handler
 * is defined. `:contract` is excluded by construction: it's a *dependency* of `:sharedLogic`, so it
 * cannot import the handler, and its only `CoroutineScope(` is a Swift-facing `StateFlow` bridge
 * utility, not an app-lifetime scope.
 */
class CoroutineScopeInstallsExceptionHandlerRule :
    FunSpec({
        test("every :sharedLogic CoroutineScope() installs appCoroutineExceptionHandler (Kotlin/Native crash-net)") {
            val offenders =
                productionScope()
                    .files
                    .filter { it.path.contains("/sharedLogic/") }
                    .filter { it.text.contains("CoroutineScope(") }
                    .filterNot { it.text.contains("appCoroutineExceptionHandler") }
                    .map { it.path }

            offenders.shouldBeEmpty()
        }
    })
