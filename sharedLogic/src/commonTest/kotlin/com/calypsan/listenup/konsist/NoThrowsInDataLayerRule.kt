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
 * exception-propagation pattern still awaiting migration to AppResult. Several
 * un-migrated APIs and repo impls remain; those files appear in
 * [RESIDUAL_THROWS_ALLOWLIST] so this rule passes today while still pinning the
 * already-migrated surface.
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
        // Auth / refresh / SSE infrastructure with throwing patterns:
        "/data/remote/ApiClientFactory.kt",
        // Repo impls still in throwing style:
        "/data/repository/AuthRepositoryImpl.kt",
        "/data/repository/AvatarDownloadRepositoryImpl.kt",
        "/data/repository/ContributorRepositoryImpl.kt",
        "/data/repository/CoverDownloadRepositoryImpl.kt",
        "/data/repository/DownloadRepositoryImpl.kt",
        "/data/repository/InstanceRepositoryImpl.kt",
        "/data/repository/LeaderboardRepositoryImpl.kt",
        "/data/repository/RegistrationStatusStreamImpl.kt",
        "/data/repository/SearchRepositoryImpl.kt",
        "/data/repository/SeriesRepositoryImpl.kt",
        "/data/repository/SessionRepositoryImpl.kt",
        "/data/repository/SettingsRepositoryImpl.kt",
    )

private fun com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration.containsDisallowedThrow(): Boolean {
    val body = text
    if (!body.contains("throw ")) return false

    // Strip allowed patterns line by line; if any throw survives, it's disallowed.
    return body.lineSequence().any { rawLine ->
        val line = rawLine.trim()
        line.contains("throw ") &&
            !line.contains("throw e") && // cancellation rethrow: throw e
            !line.contains("throw cause") && // cancellation rethrow: throw cause
            !line.contains("throw NotImplementedError(") && // placeholder
            !line.contains("throw EnvelopeMismatchException(") && // protocol contract guard
            // not-connected guard: RPC-proxy factories throw this typed exception when no server
            // URL is configured (an expected pre-connection state). ErrorMapper folds it to a
            // transient NetworkUnavailable; it is a configuration guard, not a swallowed failure.
            !line.contains("throw ServerUrlNotConfiguredException(")
    }
}
