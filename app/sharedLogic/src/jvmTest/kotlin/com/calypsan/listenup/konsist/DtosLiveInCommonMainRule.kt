package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.serialization.Serializable

/**
 * Konsist guard pinning where `@Serializable` types live, in two tiers:
 *
 * 1. **Wire types live in the contract.** A `@Serializable` class in package `com.calypsan.listenup.api` (and below)
 *    — the package of every `@Rpc` signature type and sync payload — must be declared under
 *    `contract/src/commonMain/`, so the server and every client compile the same declaration. Being in *a*
 *    `commonMain` is not enough: the server's own sources are `server/src/commonMain` now, and a wire DTO declared
 *    there, with a hand-copied twin in the client, would drift with nothing to notice.
 * 2. **Everything else is in a `commonMain`,** except the reviewed path prefixes in [PLATFORM_SERIALIZABLE_HOMES],
 *    each with its reason. Each prefix must still hold at least one class, so the list cannot rot into a blanket
 *    exemption.
 */
class DtosLiveInCommonMainRule :
    FunSpec({
        val serializableClasses = productionScope().classes().withAnnotationOf(Serializable::class)

        test("the scope reaches the @Serializable classes, or this rule polices nothing") {
            assertScopeNotEmpty(
                serializableClasses,
                expectedMin = 250,
                why = "every @Serializable class — if annotation resolution breaks, the rule polices nothing",
            )
        }

        test("every @Serializable class in the api package lives in the contract's commonMain") {
            val offenders =
                serializableClasses
                    .filter {
                        it.packagee?.name?.let { pkg -> pkg == API_PACKAGE || pkg.startsWith("$API_PACKAGE.") } ==
                            true
                    }.filterNot { CONTRACT_COMMON_MAIN in it.path }
                    .map { "${it.fullyQualifiedName ?: it.name} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }

        test("every other @Serializable class lives in a commonMain source set, or a reviewed platform home") {
            val offenders =
                serializableClasses
                    .filterNot { "/commonMain/" in it.path }
                    .filterNot { cls -> PLATFORM_SERIALIZABLE_HOMES.keys.any { it in cls.path } }
                    .map { "${it.fullyQualifiedName ?: it.name} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }

        test("every reviewed platform home still holds a @Serializable class") {
            val stale = PLATFORM_SERIALIZABLE_HOMES.keys.filter { home -> serializableClasses.none { home in it.path } }

            stale.shouldBeEmpty()
        }
    })

private const val API_PACKAGE = "com.calypsan.listenup.api"

private const val CONTRACT_COMMON_MAIN = "/contract/src/commonMain/"

/**
 * Path prefixes where a non-commonMain `@Serializable` class is legitimate, each with why it crosses no wire.
 * Scoped to the package, not the module: a module-wide exemption would silently bless a wire-borne type landing
 * anywhere in it.
 */
private val PLATFORM_SERIALIZABLE_HOMES =
    mapOf(
        "/app/sharedUI/src/androidMain/kotlin/com/calypsan/listenup/client/navigation/" to
            "Android Navigation 3 routes: @Serializable for type-safe nav arguments, never sent over the wire",
        "/app/webApp/src/jsMain/kotlin/com/calypsan/listenup/web/features/licences/" to
            "the shape of web/public/licences.json, a build artifact this repo generates and this one client reads",
    )
