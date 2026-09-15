package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.serialization.Serializable

/**
 * Konsist guard pinning the rule that every wire-borne `@Serializable` type lives in a
 * commonMain source set, so the JVM (server) and KMP (client) compilers see the exact same
 * bytes when serializing/deserializing.
 *
 * Allowlisted (NOT wire-borne, legitimate platform-specific @Serializable use):
 * - `:app:sharedUI/.../androidMain/...` — Android Navigation 3 routes use `@Serializable` for
 *   compile-time type-safe nav arg passing, not for cross-platform wire transport.
 * - `:server/src/jvmMain/` — persistence-internal @Serializable types (DB row classes, job
 *   payloads). If a new wire-borne type lands in `:server` instead of commonMain, this rule
 *   must be tightened or the type moved — that's the regression this rule blocks.
 * - `:app:webApp/.../features/licences/` — the shape of `web/public/licences.json`, a build
 *   artifact this repo generates (`:app:webApp:mergeWebLicences`) and this one client reads. It
 *   crosses no boundary, so there are no two compilers to keep in agreement. Scoped to the package
 *   rather than the module on purpose: `:app:webApp` is JS-only and has no commonMain at all, so a
 *   module-wide exemption would silently bless a genuinely wire-borne type landing anywhere in it.
 */
class DtosLiveInCommonMainRule :
    FunSpec({
        test("every wire-borne @Serializable data class lives in a commonMain source set") {
            val serializableClasses = productionScope().classes().withAnnotationOf(Serializable::class)

            assertScopeNotEmpty(
                serializableClasses,
                expectedMin = 250,
                why = "every @Serializable class — if annotation resolution breaks, the rule polices nothing",
            )

            val offenders =
                serializableClasses
                    .filter { !it.path.contains("/commonMain/") }
                    // Allowlist: see KDoc on this rule for justification.
                    .filter { !it.path.contains("/server/src/jvmMain/") }
                    .filter { !it.path.contains("/sharedUI/") }
                    .filter {
                        !it.path.contains(
                            "/webApp/src/jsMain/kotlin/com/calypsan/listenup/web/features/licences/",
                        )
                    }.map { "${it.fullyQualifiedName} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
