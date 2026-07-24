package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Konsist guard pinning the rule that domain code (use cases, repositories, domain models,
 * presentation ViewModels) must not import transport-layer types directly. The data layer
 * is the boundary: it consumes `kotlinx.rpc` and `io.ktor` and exposes `AppResult<T>` of
 * domain models to layers above. Domain layer mocking and platform portability both
 * depend on this separation.
 *
 * **Presentation is now actually covered.** The KDoc has always named "presentation ViewModels",
 * but the filter matched `/domain/` only — so every ViewModel was exempt from the rule that
 * claimed to police it, and `ServerConnectViewModel` imports Ktor today. Rather than quietly
 * narrow the promise, the scope now matches the wording; the single legitimate case is listed in
 * [TRANSPORT_IMPORT_ALLOWLIST] and justified there.
 */
private val DOMAIN_PATHS = listOf("/domain/", "/presentation/")

private val TRANSPORT_PACKAGE_PREFIXES = listOf("kotlinx.rpc.", "io.ktor.")

/**
 * Files allowed to import a transport package, with the reason.
 *
 * Keep this short and justify every entry: an allowlist is how a guard quietly stops guarding.
 *
 *  - `ServerConnectViewModel` — uses `io.ktor.http.Url` purely as a URL *syntax parser* (parse,
 *    catch `URLParserException`, return `ServerConnectError.InvalidUrl`). No client, no request,
 *    no transport: it is the standard multiplatform URL parser, so neither portability nor
 *    mockability — the two things this rule exists to protect — is affected. The alternative is
 *    hand-rolling URL validation, which is strictly worse.
 */
private val TRANSPORT_IMPORT_ALLOWLIST =
    setOf("/client/presentation/connect/ServerConnectViewModel.kt")

class NoTransportTypesInDomainRule :
    FunSpec({
        test("no commonMain domain or presentation code imports kotlinx.rpc or io.ktor symbols") {
            val scoped =
                productionScope()
                    .files
                    .filter { it.path.contains("/commonMain/") }
                    .filter { file -> DOMAIN_PATHS.any { file.path.contains(it) } }

            // Vacuity guard: a package-layout change would otherwise leave this matching nothing
            // and passing green — the failure mode that hollowed out the sync-substrate rules.
            scoped.shouldNotBeEmpty()

            val offenders =
                scoped
                    .filterNot { file -> TRANSPORT_IMPORT_ALLOWLIST.any { file.path.endsWith(it) } }
                    .flatMap { file ->
                        file.imports
                            .filter { import -> TRANSPORT_PACKAGE_PREFIXES.any { import.name.startsWith(it) } }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
