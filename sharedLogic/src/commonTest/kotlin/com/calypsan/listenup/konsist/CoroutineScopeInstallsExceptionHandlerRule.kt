package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Konsist guard pinning the iOS/macOS crash-net: every production `CoroutineScope(...)` must install
 * [com.calypsan.listenup.core.appCoroutineExceptionHandler].
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
 * holds exactly one construction, so "contains `CoroutineScope(` ⇒ contains
 * `appCoroutineExceptionHandler`" is precise enough.
 *
 * **Comments are stripped first, and that is load-bearing.** Presence-matching raw text means a
 * *mention* of the handler satisfies the rule: writing "the scope installs
 * `appCoroutineExceptionHandler`" in a KDoc made an unguarded scope pass, which is exactly how this
 * rule was caught missing the `:contract` bridge below. Stripping comments also removes the handler
 * definition file's own KDoc example (`CoroutineScope(SupervisorJob() + … )`), so it drops out of the
 * candidate set naturally instead of needing an allowlist.
 *
 * **Why it now covers `:contract` too.** It used to be scoped to `:sharedLogic`, excluding `:contract`
 * on the reasoning that it "cannot import the handler, and its only `CoroutineScope(` is a Swift-facing
 * `StateFlow` bridge utility, not an app-lifetime scope." The second half was wrong: lifetime has
 * nothing to do with this crash class — one uncaught throw terminates the process from any scope. And
 * that bridge is the path *every* Swift observer runs through, invoking an arbitrary Swift callback
 * that reads bridged Kotlin properties, which made it the highest-traffic unguarded `launch` in the
 * app. The first half was a real constraint, now removed: the handler moved down into `:contract`
 * (`com.calypsan.listenup.core`), which `:sharedLogic` re-exports via `api(projects.contract)`, so both
 * modules share one definition and one rule.
 */
class CoroutineScopeInstallsExceptionHandlerRule :
    FunSpec({
        test("every production CoroutineScope() installs appCoroutineExceptionHandler (Kotlin/Native crash-net)") {
            val scopeFiles =
                productionScope()
                    .files
                    .filter { it.path.contains("/sharedLogic/") || it.path.contains("/contract/") }
                    .map { it.path to stripComments(it.text) }
                    .filter { (_, code) -> code.contains("CoroutineScope(") }

            // Vacuity guard: a module-path rename would otherwise leave this matching nothing and
            // passing green — the failure mode that hollowed out the sync-substrate rules.
            scopeFiles.shouldNotBeEmpty()

            val offenders =
                scopeFiles
                    .filterNot { (_, code) -> code.contains("appCoroutineExceptionHandler") }
                    .map { (path, _) -> path }

            offenders.shouldBeEmpty()
        }
    })

/** Strips line and block comments so a *mention* of the handler can't satisfy the rule. */
private fun stripComments(source: String): String =
    source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
