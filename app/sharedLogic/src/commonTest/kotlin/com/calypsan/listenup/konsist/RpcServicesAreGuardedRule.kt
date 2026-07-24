package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard asserting that every service-registration call site passes its
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
 * whose text registers services, the number of `guard(` tokens must be at least
 * as large as the number of registration call sites. A function that registers
 * three services must call `guard(` at least three times.
 *
 * **Two registration verbs.** Services reach the wire either directly via
 * `registerService<Concrete> { guard(...) }` (the anonymous `/api/rpc/public`
 * surface) or via the `registerScoped<Concrete> { guard(...) }` helper (the
 * principal-scoped `/api/rpc/authed` surface). Both are counted as registrations
 * here, so an unguarded call site of either form fails the rule.
 *
 * **The helper is trusted plumbing.** `registerScoped` itself wraps a single
 * `registerService<T>` and forwards a [com.calypsan.listenup.server.auth.PrincipalProvider]
 * to its `scoped` lambda — the `guard(...)` lives at each call site, not inside the
 * helper. Its body therefore has a registration token but no inline guard *by design*,
 * so the helper definition is excluded from the count. The invariant it upholds —
 * "every `registerScoped<...>` call site guards" — is enforced on those call sites above.
 *
 * Line and block comments are stripped from the function text before counting,
 * so neither code-shaped comments (`// guard(...)`) nor docstrings can inflate
 * the guard count and hide an unguarded registration.
 *
 * Production call sites validated by this rule (all in `RpcRoutes.kt`):
 * - `registerService<PingService> { guard(PingServiceImpl()) }`
 * - `registerService<AuthServicePublic> { guard(authService as AuthServicePublic) }`
 * - `registerService<ScannerService> { guard(scannerService) }`
 * - `registerScoped<AuthServiceAuthed> { guard(authService.copyWith(it) as AuthServiceAuthed) }`
 * - `registerScoped<BookService> { guard((bookService as BookServiceImpl).copyWith(it)) }`
 */
class RpcServicesAreGuardedRule :
    FunSpec({
        test("every service registration passes its impl through guard(...)") {
            val registrationToken = Regex("""register(Service|Scoped)<""")
            val offenders =
                productionScope()
                    .functions()
                    // Exclude the registerScoped helper definition: it is the trusted plumbing
                    // that forwards guarding to its call sites (see KDoc).
                    .filterNot { fn -> fn.name == "registerScoped" }
                    .filter { fn -> registrationToken.containsMatchIn(fn.text) }
                    .filter { fn ->
                        val body = stripComments(fn.text)
                        val registerCount = registrationToken.findAll(body).count()
                        val guardCount = Regex("""\bguard\s*\(""").findAll(body).count()
                        guardCount < registerCount
                    }.map { fn -> "${fn.name} @ ${fn.path}" }

            offenders.shouldBeEmpty()
        }
    })

private fun stripComments(source: String): String =
    source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
