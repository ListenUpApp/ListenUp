package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Every `@Rpc` interface in the contract is mounted by the server, on both the JVM and the
 * native entry points.
 *
 * An `@Rpc` interface that is never registered compiles, passes its own tests and fails only at
 * runtime on a client, as a "service not found". This rule makes that a build failure, and also
 * catches the two platform route files drifting apart.
 *
 * **Declared** is every interface under `contract/src/commonMain` carrying `@Rpc`. **Registered**
 * is every `registerService<T>` / `registerScoped<T>` type argument in `RpcRoutes.jvm.kt` and
 * `RpcRoutes.linux.kt`, comments stripped so a commented-out registration does not count.
 */
class EveryRpcServiceIsRegisteredRule :
    FunSpec({
        test("every @Rpc interface is registered on the JVM and the native server") {
            val scope = productionScope()
            val declared =
                scope
                    .interfaces()
                    .filter { "/contract/src/commonMain/" in it.path && it.hasAnnotationWithName("Rpc") }
                    .map { it.name }
                    .toSet()

            assertScopeNotEmpty(
                declared,
                expectedMin = 25,
                why = "@Rpc interfaces in contract/src/commonMain (35 today); fewer means discovery collapsed.",
            )

            fun registeredIn(routesFileSuffix: String): Set<String> {
                val file = scope.files.single { it.path.endsWith(routesFileSuffix) }
                return REGISTRATION.findAll(stripComments(file.text)).map { it.groupValues[1] }.toSet()
            }

            val jvm = registeredIn("/server/src/jvmMain/kotlin/com/calypsan/listenup/server/routes/RpcRoutes.jvm.kt")
            val linux = registeredIn("/server/src/linuxMain/kotlin/com/calypsan/listenup/server/routes/RpcRoutes.linux.kt")

            withClue("RpcRoutes.jvm.kt is missing ${declared - jvm}; registers non-@Rpc ${jvm - declared}") {
                jvm shouldBe declared
            }
            withClue("RpcRoutes.linux.kt is missing ${declared - linux}; registers non-@Rpc ${linux - declared}") {
                linux shouldBe declared
            }
        }
    })

private val REGISTRATION = Regex("""register(?:Service|Scoped)<(\w+)>""")
