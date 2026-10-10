package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Every `registerScoped` registration must hand the connection's liveness probe to `guard`. The
 * probe is an optional argument, so a registration that forgets it compiles and silently exempts a
 * whole service from the revoked-session check; this makes that omission a failure.
 */
class ScopedRegistrationsArmLivenessTest :
    FunSpec({
        test("every scoped registration passes the liveness probe to guard") {
            val jvmFile =
                Konsist
                    .scopeFromProduction()
                    .files
                    .first { file ->
                        file.path.endsWith(
                            "/server/src/jvmMain/kotlin/com/calypsan/listenup/server/routes/RpcRoutes.jvm.kt",
                        )
                    }
            val registrations = stripComments(File(jvmFile.path).readText()).split("registerScoped<").drop(1)
            val armed = Regex("""guard\([^;]*\blive\b""", RegexOption.DOT_MATCHES_ALL)

            (registrations.size >= MINIMUM_REGISTRATIONS) shouldBe true
            registrations.filterNot { armed.containsMatchIn(it) }.map { it.substringBefore('>') }.shouldBeEmpty()
        }
    })

private const val MINIMUM_REGISTRATIONS = 25
