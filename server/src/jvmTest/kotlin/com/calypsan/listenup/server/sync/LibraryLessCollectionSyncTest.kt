package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.publicAuthService

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.ktor.server.testing.testApplication

/**
 * Regression guard: on a **library-less** boot (no `scanner.libraryPath`), the always-available
 * collection-sync pull surface resolves `BookAccessPolicy` via `accessFilterFor` for every
 * non-admin caller. `BookAccessPolicy` must therefore be bound in the always-loaded
 * [com.calypsan.listenup.server.di.syncModule]; a missing binding surfaces as a
 * `NoDefinitionFoundException`, not as a clean empty page. (It historically lived in
 * the then-library-gated `booksModule`, which failed this path on a library-less boot.)
 *
 * This test boots library-less, registers a non-admin member, and asserts each collection-sync
 * domain's `pullDomain` call succeeds with an empty page.
 */
class LibraryLessCollectionSyncTest :
    FunSpec({

        test("library-less boot: collection-sync pullDomain succeeds for a member, not PermissionDenied") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                mintRootToken()
                val memberToken = registerMember()

                listOf(
                    "collections",
                    "collection_books",
                    "collection_shares",
                ).forEach { domain ->
                    authedService<SyncStreamService>(memberToken)
                        .pullDomain(domain, since = 0, limit = 500)
                        .shouldSucceed()
                }
            }
        }
    })

private suspend fun ApplicationTestBuilder.mintRootToken(): String =
    publicAuthService()
        .setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

private suspend fun ApplicationTestBuilder.registerMember(): String {
    val result =
        publicAuthService()
            .register(RegisterRequest("member@x", "y".repeat(8), "Member"))
            .let { it as AppResult.Success<com.calypsan.listenup.api.dto.auth.RegisterResult> }
            .data
    return (result as com.calypsan.listenup.api.dto.auth.RegisterResult.Authenticated).session.accessToken.value
}
