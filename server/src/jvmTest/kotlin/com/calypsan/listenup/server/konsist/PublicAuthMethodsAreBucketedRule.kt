package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Every method on every service registered on the unauthenticated `/api/rpc/public` mount is
 * throttled, or is a listed exemption with a reason.
 *
 * `/api/rpc/public` is the one mount an unauthenticated stranger can reach, and the per-IP token
 * buckets (`AuthRateBucket` / `InviteRateBucket` + each implementation's `enforceRate`) are the whole
 * of what makes that safe — the mount has no REST mirror and no Ktor route-level limiter, and many
 * calls ride one WebSocket, so throttling the upgrade is useless. `AuthRateBucket` states the invariant
 * this rule pins, in `RESET_ROOT_PASSWORD`'s own KDoc:
 *
 * > "Consistency is the point: an un-bucketed auth method is an exception a reader has to go and
 * > verify, so it should not exist without a reason."
 *
 * `observePasswordResetStatus` is what that costs when it is only prose: it shipped un-bucketed
 * while both of its `observe*` siblings were bucketed. Invite claim and lookup are the mount's other
 * credential oracle (a guessable invite code), yet the rule once read only `AuthServicePublic`. So the
 * population is now read from the **mount itself** — the `registerService<X>` type arguments inside
 * `rpc("/api/rpc/public") { … }` — and the methods from each **contract** interface, so a service or
 * method added to the public surface is covered the moment it exists.
 */
class PublicAuthMethodsAreBucketedRule :
    FunSpec({

        /**
         * Methods that are public and deliberately un-bucketed, keyed by service. Each needs a
         * stated reason; this map is the second place a reader can find them.
         */
        val exempt =
            mapOf(
                // Costs a signature verification, requires a valid token to yield anything, and a client
                // legitimately mints one per reconnect — a bucket would throttle recovery from exactly the
                // network conditions that cause reconnects.
                "AuthServicePublic" to setOf("issueSocketTicket"),
                // One cheap read of the server's public identity (name, version, setup state) that every
                // client makes before signing in; it takes no input, so it is no credential oracle.
                "InstanceService" to setOf("getServerInfo"),
            )

        test("every method on the public RPC mount is bucketed or exempt") {
            val publicServices = publicMountServices()

            // A conscious edit: adding a service to the unauthenticated mount must be seen here.
            publicServices shouldBe setOf("InstanceService", "AuthServicePublic", "InviteServicePublic")

            val production = Konsist.scopeFromProduction()
            val offenders = mutableListOf<String>()
            var checkedCount = 0

            publicServices.forEach { service ->
                val contract = production.interfaces().filter { it.name == service }
                contract shouldHaveSize 1
                // AuthServicePublic → AuthServiceImpl, InviteServicePublic → InviteServiceImpl, X → XImpl.
                val implementationName = service.removeSuffix("Public") + "Impl"
                val implementations = production.classes().filter { it.name == implementationName }
                implementations shouldHaveSize 1
                val implementation = implementations.single()

                val checked =
                    contract
                        .single()
                        .functions()
                        .map { it.name }
                        .filterNot { it in exempt[service].orEmpty() }
                checkedCount += checked.size
                checked.forEach { name ->
                    val override = implementation.functions().firstOrNull { it.name == name }
                    when {
                        override == null -> {
                            offenders += "$service.$name has no override in $implementationName"
                        }

                        !stripComments(override.text).contains("enforceRate(") -> {
                            offenders += "$service.$name does not call enforceRate(...) in $implementationName — " +
                                "bucket it, or add it to `exempt` with a reason"
                        }
                    }
                }
            }

            // Guard against a vacuous pass: the public mount carries a dozen-plus methods, and a rule
            // that silently matches nothing has stopped guarding.
            checkedCount shouldBeGreaterThanOrEqual 10
            offenders.shouldBeEmpty()
        }

        test("every exemption names a method its public contract still declares") {
            val production = Konsist.scopeFromProduction()
            val stale =
                exempt.flatMap { (service, methods) ->
                    val declared =
                        production
                            .interfaces()
                            .filter { it.name == service }
                            .flatMap { it.functions() }
                            .map { it.name }
                    methods.filterNot { it in declared }.map { "$service.$it" }
                }
            stale.shouldBeEmpty()
        }
    })

/** The `registerService<X>` type arguments inside the `rpc("/api/rpc/public") { … }` block, comments stripped. */
private fun publicMountServices(): Set<String> {
    val source =
        stripComments(
            File(
                Konsist.projectRootPath,
                "server/src/jvmMain/kotlin/com/calypsan/listenup/server/routes/RpcRoutes.jvm.kt",
            ).readText(),
        )
    val start = source.indexOf("rpc(\"/api/rpc/public\")")
    check(start >= 0) { "the public RPC mount is no longer declared in RpcRoutes.jvm.kt" }
    val open = source.indexOf('{', start)
    var depth = 0
    var end = source.length
    for (index in open until source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> depth--
        }
        if (depth == 0) {
            end = index
            break
        }
    }
    return Regex("""registerService<(\w+)>""")
        .findAll(source.substring(open, end))
        .map { it.groupValues[1] }
        .toSet()
}
