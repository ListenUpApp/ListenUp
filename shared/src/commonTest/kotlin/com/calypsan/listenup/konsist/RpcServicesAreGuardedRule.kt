package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard asserting that every `registerService<T>` call site passes its
 * implementation through `guard(...)`.
 *
 * The guard decorator (KSP-generated `<Service>Guarded`) is the final line of
 * defence that converts unhandled exceptions into typed, sanitised
 * [com.calypsan.listenup.api.error.AppError] values before they cross the RPC
 * wire. Forgetting the wrap is a security and stability regression — a raw
 * service impl would ship internal stacktraces to clients.
 *
 * The Konsist AST API does not provide deep lambda-body traversal across all
 * call forms, so this rule uses a string-matching heuristic: for any function
 * whose text contains at least one `registerService<` token, the number of
 * `guard(` tokens must be at least as large. A function that calls
 * `registerService<X>` three times must call `guard(` at least three times.
 *
 * **Known limitation:** if `guard(...)` is delegated to a helper function called
 * from the same function body, the helper's `guard(` invocation will not be
 * counted here and the rule will false-fail. In that case, either inline the
 * guard call or update this rule to recognise the helper pattern. As of the
 * initial roll-out, all `registerService<...>` call sites live in
 * `RpcRoutes.kt` and wrap inline — the simple count comparison is sufficient.
 *
 * Production call sites validated by this rule (all in `RpcRoutes.kt`):
 * - `registerService<PingService> { guard(PingServiceImpl()) }`
 * - `registerService<AuthServicePublic> { guard(authService as AuthServicePublic) }`
 * - `registerService<ScannerService> { guard(scannerService) }`
 * - `registerService<AuthServiceAuthed> { guard(authService.copyWith(...) as AuthServiceAuthed) }`
 */
class RpcServicesAreGuardedRule :
    FunSpec({
        test("every registerService<T> { ... } factory passes its impl through guard(...)") {
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .functions()
                    .filter { fn -> fn.text.contains("registerService<") }
                    .filter { fn ->
                        val registerCount = Regex("""registerService<""").findAll(fn.text).count()
                        val guardCount = Regex("""\bguard\s*\(""").findAll(fn.text).count()
                        guardCount < registerCount
                    }.map { fn -> "${fn.name} @ ${fn.path}" }

            offenders.shouldBeEmpty()
        }
    })
