package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Pins `RpcRoutes.jvm.kt` and `RpcRoutes.linux.kt` byte-identical. They are separate `actual`s (JVM vs the
 * shared linux source set) only because the KSP-generated `guard(...)` is commonMain-invisible (generated
 * per native target into :contract);
 * see RpcRoutes.kt. Nothing else forces them to match, so this guard catches silent drift between the
 * JVM and native RPC mounts. If you intentionally change one, change the other identically.
 */
class RpcRoutesActualsParityTest :
    FunSpec({
        test("RpcRoutes jvm and shared-linux actuals are byte-identical") {
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
                    .replace("/jvmMain/", "/linuxMain/")
                    .replace("RpcRoutes.jvm.kt", "RpcRoutes.linux.kt")
            val nativeText = File(nativePath).readText()
            nativeText shouldBe jvmText
        }
    })
