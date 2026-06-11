package com.calypsan.listenup.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutValueModifier
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.Serializable

/**
 * Architectural assertions for the contract boundary. These are the
 * structural invariants Phase 1 set out to make true and Phase G1 pins in CI.
 *
 * Rules:
 *  1. `@Serializable` DTOs in `com.calypsan.listenup.api..` are in commonMain
 *     (so both server and clients consume the same source).
 *  2. Domain code (`com.calypsan.listenup.client.domain..`) imports no
 *     transport types — Ktor or kotlinx.rpc — keeping the boundary clean.
 *  3. No `:server` symbols (`com.calypsan.listenup.server..`) are imported
 *     outside the `:server` module.
 *
 * Each rule fails the build with a list of offenders. The plan also called
 * out two more rules — "every @Rpc has a matching @Resource" and "every
 * Koin leaf module has a `module.verify()` test" — both are deferred:
 * `PingService` is a smoke-test service that deliberately has no REST mirror,
 * and `module.verify()` coverage is already enforced ad-hoc by
 * `AuthModuleVerifyTest` + `AuthModuleVerifyTest` server-side. Those rules
 * become useful once we have multiple feature modules to compare; today
 * they would mostly false-positive.
 */
class ArchitectureTest :
    FunSpec({

        test("@Serializable DTOs in the api package live in commonMain") {
            Konsist
                .scopeFromProject()
                .classes()
                .filter { it.resideInPackage("com.calypsan.listenup.api..") }
                .withoutValueModifier()
                .filter { it.hasAnnotationOf(Serializable::class) }
                .assertTrue { koClass ->
                    "/contract/src/commonMain/" in koClass.containingFile.path
                }
        }

        test("client domain code has no transport-layer imports") {
            Konsist
                .scopeFromProject()
                .files
                .filter { it.packagee?.name?.startsWith("com.calypsan.listenup.client.domain") == true }
                .assertFalse { file ->
                    file.imports.any { import ->
                        val name = import.name
                        name.startsWith("io.ktor.") || name.startsWith("kotlinx.rpc.")
                    }
                }
        }

        test("no :server symbols are imported outside the :server module") {
            Konsist
                .scopeFromProject()
                .files
                .filter { "/server/src/main/" !in it.path && "/server/src/test/" !in it.path }
                // C3 e2e fixture intentionally drives the real `:server` testApplication
                // in-process. Confined to `:sharedLogic:jvmTest` so production code is
                // unaffected; the fixture is the documented seam.
                .filter { "/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/client/data/sync/testing/" !in it.path }
                // DI-wired client e2e fixture: boots the real server module in-process to
                // exercise the full Koin graph and authenticate against it. Same exemption
                // class as the C3 sync fixture above — confined to jvmTest, not production.
                .filter { "/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/client/di/e2e/" !in it.path }
                // Profile E2E: boots the real ProfileService in-process via testApplication to
                // exercise the updateMyProfile → getMyProfile RPC round-trip. Same exemption
                // class as the sync and DI fixtures above — confined to jvmTest, not production.
                .filter { "/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/client/profile/" !in it.path }
                // Cross-stack digest parity test: drives the real server `digest()` against the client
                // `DigestComputer` to prove byte-identical algorithms. Confined to jvmTest, not production.
                .filter { "/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/client/data/sync/DigestParityE2ETest" !in it.path }
                // Digest reconciliation gap E2E: boots real server in-process under FirehoseSuppressed to
                // manufacture a sub-floor gap row, then proves reconcileAll repairs it. Same exemption
                // class as DigestParityE2ETest — confined to jvmTest, not production.
                .filter { "/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/client/data/sync/DigestReconcileGapE2ETest" !in it.path }
                // Backup RPC E2E: drives the client BackupRepository through the real BackupService
                // in-process to prove the admin backup routes return domain-typed results (not a
                // transport 404). Same exemption class as the Profile E2E — confined to jvmTest.
                .filter { "/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/client/admin/" !in it.path }
                .assertFalse { file ->
                    file.imports.any { it.name.startsWith("com.calypsan.listenup.server.") }
                }
        }

        test("ScanEvent variants live in commonMain") {
            Konsist
                .scopeFromProject()
                .classes()
                .filter { it.resideInPackage("com.calypsan.listenup.api.event..") }
                .withoutValueModifier()
                .assertTrue { koClass ->
                    "/contract/src/commonMain/" in koClass.containingFile.path
                }
        }

        test("scanner package files in :server have no io.ktor imports") {
            // The scanner core stays transport-agnostic. Ktor only enters via
            // `routes/ScannerRoutes.kt` (REST + SSE) and `di/ScannerModule.kt`
            // (Koin's ApplicationConfig binding) — both outside `scanner/`.
            Konsist
                .scopeFromProject()
                .files
                .filter { "/server/src/main/" in it.path }
                .filter { it.packagee?.name?.startsWith("com.calypsan.listenup.server.scanner") == true }
                .assertFalse { file ->
                    file.imports.any { it.name.startsWith("io.ktor.") }
                }
        }
    })
