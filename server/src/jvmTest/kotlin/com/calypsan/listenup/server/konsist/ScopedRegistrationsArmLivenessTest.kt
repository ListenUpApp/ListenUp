package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Every `registerScoped` registration must hand the connection's liveness probe to `guard`. The
 * probe is an optional argument, so a registration that forgets it compiles and silently exempts a
 * whole service from the revoked-session check; this makes that omission a failure.
 *
 * The number of scoped registrations is pinned exactly, not by a floor: the anonymous surface
 * (`registerService<…>`, no principal and no liveness probe) is the fixed [ANONYMOUS_SERVICES] set, and
 * every other `@Rpc` interface in `contract/src/commonMain` must be one `registerScoped<…>` block. A
 * service moved to a raw `registerService<…>`, or a scoped block that disappeared, fails the test.
 */
class ScopedRegistrationsArmLivenessTest :
    FunSpec({
        test("every scoped registration passes the liveness probe to guard") {
            val scope = Konsist.scopeFromProduction()
            val jvmFile =
                scope.files.first { file ->
                    file.path.endsWith(
                        "/server/src/jvmMain/kotlin/com/calypsan/listenup/server/routes/RpcRoutes.jvm.kt",
                    )
                }
            val source = stripComments(File(jvmFile.path).readText())
            val registrations = source.split("registerScoped<").drop(1)
            val anonymous = ANONYMOUS_REGISTRATION.findAll(source).map { it.groupValues[1] }.toList()
            val declaredRpcInterfaces =
                scope
                    .interfaces()
                    .count { "/contract/src/commonMain/" in it.path && it.hasAnnotationWithName("Rpc") }
            val armed = Regex("""guard\([^;]*\blive\b""", RegexOption.DOT_MATCHES_ALL)

            withClue("@Rpc interfaces discovered in contract/src/commonMain (36 today); fewer means discovery collapsed") {
                (declaredRpcInterfaces >= MINIMUM_RPC_INTERFACES) shouldBe true
            }
            withClue("registerService<…> mounts a service with no principal and no liveness probe") {
                anonymous.sorted() shouldBe ANONYMOUS_SERVICES.sorted()
            }
            withClue(
                "$declaredRpcInterfaces @Rpc interfaces, ${ANONYMOUS_SERVICES.size} anonymous: " +
                    "the rest must each be one registerScoped<…> block",
            ) {
                registrations.size shouldBe declaredRpcInterfaces - ANONYMOUS_SERVICES.size
            }
            registrations.filterNot { armed.containsMatchIn(it) }.map { it.substringBefore('>') }.shouldBeEmpty()
        }
    })

private val ANONYMOUS_REGISTRATION = Regex("""registerService<(\w+)>""")

/**
 * The services callable before sign-in: a health ping, instance discovery, and the public halves of
 * auth and invites (their signed-in halves are separate `@Rpc` interfaces, registered scoped).
 */
private val ANONYMOUS_SERVICES = listOf("PingService", "InstanceService", "AuthServicePublic", "InviteServicePublic")

private const val MINIMUM_RPC_INTERFACES = 30
