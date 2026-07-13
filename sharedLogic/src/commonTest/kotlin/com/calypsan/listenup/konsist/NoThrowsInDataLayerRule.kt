package com.calypsan.listenup.konsist

import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pins the data-layer error contract.
 *
 * Fallible work in `data/remote/` and `data/repository/` returns
 * [com.calypsan.listenup.api.result.AppResult] rather than throwing. This rule
 * detects functions whose body contains `throw` expressions, then filters out
 * patterns that are still legitimate:
 *
 * - **Cooperative cancellation rethrows** (`throw e`, `throw cause`) — the
 *   `throw e` after `catch (e: CancellationException)` pattern that every
 *   suspend boundary needs and that AppResult does not replace.
 * - **Programmer-error contract guards** (`throw EnvelopeMismatchException(`) —
 *   the API envelope's structural canary, raised when the server has shipped a
 *   protocol-incompatible payload. This is a build-against-the-server-contract
 *   mismatch, not a runtime business error.
 * - **Placeholders** (`throw NotImplementedError(`) — `DownloadRepositoryImpl`
 *   stubs out platform code that is not yet implemented.
 *
 * After those exclusions, any `throw` left over represents a residual data-layer
 * exception-propagation pattern still awaiting migration to AppResult. One file
 * ([data/remote/ApiClientFactory.kt][RESIDUAL_THROWS_ALLOWLIST], which rethrows the
 * original throwable after a failed retry) remains and is allowlisted so this rule
 * passes today while still pinning the already-migrated surface.
 *
 * **The allowlist is not a discrete clean-up backlog** — the project is being
 * rewritten in place, so each entry naturally migrates to AppResult when its
 * domain is re-touched. Removing a file from the allowlist (i.e. fully migrating
 * it) is the explicit signal that one of those re-touches has shipped. The
 * allowlist keeps the migration debt visible without forcing a dedicated PR.
 */
class NoThrowsInDataLayerRule :
    FunSpec({
        test("data/remote/ functions don't throw outside the documented allowlist") {
            val offenders =
                productionScope()
                    .functions()
                    .filter { it.path.contains("/data/remote/") }
                    // `data/remote/model/` holds DTOs + pure mappers (e.g. Instant parsing).
                    // Those are utility functions, not API-call boundaries — the
                    // AppResult contract applies to API methods, not parser utilities.
                    .filter { !it.path.contains("/data/remote/model/") }
                    .filter { fn -> fn.containsDisallowedThrow() }
                    .filter { fn -> RESIDUAL_THROWS_ALLOWLIST.none { allowed -> fn.path.endsWith(allowed) } }
                    .map { "${it.name} in ${it.path}" }

            offenders.shouldBeEmpty()
        }

        test("data/repository/*Impl functions don't throw outside the documented allowlist") {
            val offenders =
                productionScope()
                    .functions()
                    .filter { it.path.contains("/data/repository/") && it.path.endsWith("Impl.kt") }
                    .filter { fn -> fn.containsDisallowedThrow() }
                    .filter { fn -> RESIDUAL_THROWS_ALLOWLIST.none { allowed -> fn.path.endsWith(allowed) } }
                    .map { "${it.name} in ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })

/**
 * Files known to still throw. The in-place rewrite will migrate
 * each entry to AppResult when its domain is re-touched; removing a file from
 * this set is the explicit signal that the migration of that area has shipped.
 *
 * Adding a new entry here should never be necessary — new fallible code in
 * `data/remote/` or `data/repository/` is expected to return `AppResult<T>`
 * from day one.
 */
private val RESIDUAL_THROWS_ALLOWLIST: Set<String> =
    setOf(
        // Auth / refresh infrastructure: after a failed retry it rethrows the ORIGINAL throwable
        // (`throw retryError` / `throw original`) rather than folding to AppResult — a genuine
        // residual propagation, not a sanctioned cancellation rethrow.
        "/data/remote/ApiClientFactory.kt",
    )

/**
 * Whole-token match for the two sanctioned cancellation rethrows: `throw e` and `throw cause`.
 *
 * A word boundary after the variable name is required so `throw exception`, `throw error`, and
 * `throw ex` (any identifier that merely *starts* with `e`) are NOT silently whitelisted — the
 * exact hole in the previous `line.contains("throw e")` substring check.
 */
private val SANCTIONED_RETHROW = Regex("""\bthrow\s+(e|cause)\b""")

private fun com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration.containsDisallowedThrow(): Boolean {
    val body = text
    if (!body.contains("throw ")) return false

    // Strip allowed patterns line by line; if any throw survives, it's disallowed.
    return body.lineSequence().any { rawLine ->
        val line = rawLine.trim()
        // A comment that merely mentions "throw" (KDoc/inline) is not a throw statement. Skipping
        // comment lines keeps prose from masquerading as a residual throw and pinning a file to the
        // allowlist it no longer needs.
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@any false
        line.contains("throw ") &&
            !SANCTIONED_RETHROW.containsMatchIn(line) && // cancellation rethrows: throw e / throw cause
            !line.contains("throw NotImplementedError(") && // placeholder
            !line.contains("throw EnvelopeMismatchException(") && // protocol contract guard
            // not-connected guard: RPC-proxy factories throw this typed exception when no server
            // URL is configured (an expected pre-connection state). ErrorMapper folds it to a
            // transient NetworkUnavailable; it is a configuration guard, not a swallowed failure.
            !line.contains("throw ServerUrlNotConfiguredException(") &&
            // outcome-unknown transport signal: RpcProxyCache throws this when an RPC frame was sent
            // but the response was lost, so it can't be retried. catchingRpcResult immediately folds
            // it to a typed AppResult.Failure(TransportError.OutcomeUnknown) — a transport signal, not
            // a propagated/swallowed failure (same rationale as ServerUrlNotConfiguredException).
            !line.contains("throw RpcOutcomeUnknownException(") &&
            // downstream-emit marker: RpcProxyCache.pipe wraps a throw from the DOWNSTREAM collector
            // in this internal marker so streaming/resubscribe can tell a consumer-side abort apart
            // from an upstream transport fault. It never escapes the file — both catch clauses unwrap
            // it and re-raise its cause. A control-flow signal, not a swallowed failure.
            !line.contains("throw DownstreamEmitException(")
    }
}
