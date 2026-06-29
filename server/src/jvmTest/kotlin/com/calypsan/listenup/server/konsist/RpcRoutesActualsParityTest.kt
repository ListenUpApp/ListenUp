package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Pins `RpcRoutes.jvm.kt` and `RpcRoutes.linuxX64.kt` byte-identical. They are per-target `actual`s only
 * because the KSP-generated `guard(...)` is commonMain-invisible (generated per-target into :contract);
 * see RpcRoutes.kt. Nothing else forces them to match, so this guard catches silent drift between the
 * JVM and native RPC mounts. If you intentionally change one, change the other identically.
 */
class RpcRoutesActualsParityTest :
    FunSpec({
        test("RpcRoutes jvm and linuxX64 actuals are byte-identical") {
            val jvmFile =
                Konsist
                    .scopeFromProduction()
                    .files
                    .first {
                        it.path.endsWith(
                            "/server/src/jvmMain/kotlin/com/calypsan/listenup/server/routes/RpcRoutes.jvm.kt",
                        )
                    }
            val jvmText = File(jvmFile.path).readText()
            val nativePath =
                jvmFile.path
                    .replace("/jvmMain/", "/linuxX64Main/")
                    .replace("RpcRoutes.jvm.kt", "RpcRoutes.linuxX64.kt")
            val nativeText = File(nativePath).readText()
            nativeText shouldBe jvmText
        }
    })
