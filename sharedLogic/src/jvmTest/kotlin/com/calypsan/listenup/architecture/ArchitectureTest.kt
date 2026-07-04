package com.calypsan.listenup.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutValueModifier
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.Serializable

/**
 * Architectural assertions for the contract boundary. These are the
 * structural invariants of the contract boundary, pinned in CI.
 *
 * Rules:
 *  1. `@Serializable` DTOs in `com.calypsan.listenup.api..` are in commonMain
 *     (so both server and clients consume the same source).
 *  2. Domain code (`com.calypsan.listenup.client.domain..`) imports no
 *     transport types — Ktor or kotlinx.rpc — keeping the boundary clean.
 *  3. No `:server` symbols (`com.calypsan.listenup.server..`) are imported
 *     outside the `:server` module.
 *
 * Each rule fails the build with a list of offenders. Two further rules —
 * "every @Rpc has a matching @Resource" and "every
 * Koin leaf module has a `module.verify()` test" — are deferred:
 * `PingService` is a smoke-test service that deliberately has no REST mirror,
 * and `module.verify()` coverage is already enforced ad-hoc by
 * `AuthModuleVerifyTest` + `AuthModuleVerifyTest` server-side. Those rules
 * become useful once there are multiple feature modules to compare; today
 * they would mostly false-positive.
 */
class ArchitectureTest :
    FunSpec({

        // Scope from the explicit module directories rather than `Konsist.scopeFromProject()`.
        // `scopeFromProject()` walks the entire repo tree from the root — including nested git
        // worktrees under `.worktrees/`. Those are gitignored, but Konsist scans the filesystem,
        // not git, so a stack of worktrees multiplies the scan by their count (50k+ files) and
        // hangs `:sharedLogic:jvmTest` for hours. Listing the real modules keeps every rule sound
        // (all modules are still scanned) while ignoring `.worktrees/`. Built once and shared
        // across rules — also avoids re-parsing the tree per test.
        // NOTE: a new Gradle module must be added here or it escapes these architectural checks.
        val moduleDirs =
            listOf(
                "androidApp",
                "baselineprofile",
                "contract",
                "desktopApp",
                "rpc-guard-ksp",
                "server",
                "sharedLogic",
                "sharedUI",
            )
        // Scope each module's `src/` (not its root): unlike `scopeFromProject()`,
        // `scopeFromDirectories()` does NOT skip `build/`, and scanning module roots would pull
        // in generated KSP output (e.g. `contract/build/generated/.../rpcguard/*Guarded.kt`, which
        // legitimately references server symbols) and trip these rules. `src/` is hand-written only.
        val projectScope = Konsist.scopeFromDirectories(moduleDirs.map { "$it/src" })
        // Guard against a misconfigured (empty) scope, which would make every `assertTrue`
        // rule pass vacuously and silently disable the architecture checks.
        require(projectScope.files.toList().isNotEmpty()) {
            "ArchitectureTest scope is empty — check moduleDirs against settings.gradle.kts"
        }

        test("@Serializable DTOs in the api package live in commonMain") {
            projectScope
                .classes()
                .filter { it.resideInPackage("com.calypsan.listenup.api..") }
                .withoutValueModifier()
                .filter { it.hasAnnotationOf(Serializable::class) }
                .assertTrue { koClass ->
                    "/contract/src/commonMain/" in koClass.containingFile.path
                }
        }

        test("client domain code has no transport-layer imports") {
            projectScope
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
            projectScope
                .files
                // Exempt the ENTIRE :server module (all source sets). :server is now multiplatform
                // (commonMain + linuxX64Main, since the Kotlin/Native port) — server code legitimately
                // imports other server symbols across packages. The rule's intent is preserved: code in
                // OTHER modules still must not import `com.calypsan.listenup.server.*`.
                .filter { "/server/src/" !in it.path }
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
                // Lifecycle-reconcile invariant E2E: boots real server in-process under FirehoseSuppressed
                // to manufacture an above-cursor gap row, then proves lifecycleReconcile's forward catch-up
                // lands it where forceReconcile cannot. Same exemption class — confined to jvmTest.
                .filter { "data/sync/LifecycleReconcileInvariantTest" !in it.path }
                // Activity lifecycle-reconcile invariant E2E: boots real server in-process under
                // FirehoseSuppressed to manufacture an above-cursor gap activity, then proves
                // lifecycleReconcile lands it where forceReconcile cannot. Same exemption class.
                .filter { "data/sync/ActivityLifecycleReconcileInvariantTest" !in it.path }
                // Backup RPC E2E: drives the client BackupRepository through the real BackupService
                // in-process to prove the admin backup routes return domain-typed results (not a
                // transport 404). Same exemption class as the Profile E2E — confined to jvmTest.
                .filter { "/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/client/admin/" !in it.path }
                // Sync-domain completeness spec: boots the real server module in-process and reads
                // GET /api/v1/sync/domains to assert contract ↔ client catalog ↔ server registrations
                // are exactly 1:1:1. Asserting against the real production DI graph is the whole point,
                // so the server import is intentional. Same exemption class — confined to jvmTest.
                .filter { "data/sync/domains/SyncDomainCompletenessSpec" !in it.path }
                .assertFalse { file ->
                    file.imports.any { it.name.startsWith("com.calypsan.listenup.server.") }
                }
        }

        test("ScanEvent variants live in commonMain") {
            projectScope
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
            projectScope
                .files
                .filter { "/server/src/jvmMain/" in it.path }
                .filter { it.packagee?.name?.startsWith("com.calypsan.listenup.server.scanner") == true }
                .assertFalse { file ->
                    file.imports.any { it.name.startsWith("io.ktor.") }
                }
        }
    })
