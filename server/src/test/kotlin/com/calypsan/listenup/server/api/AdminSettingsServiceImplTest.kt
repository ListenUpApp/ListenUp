package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [AdminSettingsServiceImpl].
 *
 * Real in-memory Flyway-migrated SQLite + real [ServerSettingsRepository]; no mocks.
 * The acting caller is supplied via a [PrincipalProvider] stub; [principalFor] binds
 * the service to a chosen `(userId, role)`.
 */
class AdminSettingsServiceImplTest :
    FunSpec({

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        // (a) getServerSettings returns defaults for ROOT
        test("getServerSettings returns default server name and null remoteUrl when unset") {
            withInMemoryDatabase {
                runTest {
                    val svc =
                        AdminSettingsServiceImpl(ServerSettingsRepository(this@withInMemoryDatabase, default = RegistrationPolicy.OPEN))
                            .copyWith(principalFor("root1", UserRole.ROOT))
                    val settings = svc.getServerSettings().shouldSucceed()
                    settings.serverName shouldBe ServerIdentity.NAME
                    settings.remoteUrl.shouldBeNull()
                }
            }
        }

        // (b) updateServerSettings persists name+remoteUrl and a fresh read reflects it
        test("updateServerSettings persists serverName and remoteUrl for ADMIN and fresh read reflects the change") {
            withInMemoryDatabase {
                runTest {
                    val svc =
                        AdminSettingsServiceImpl(ServerSettingsRepository(this@withInMemoryDatabase, default = RegistrationPolicy.OPEN))
                            .copyWith(principalFor("a1", UserRole.ADMIN))
                    val updated =
                        svc
                            .updateServerSettings(
                                AdminServerSettingsPatch(serverName = "My Server", remoteUrl = "https://example.com"),
                            ).shouldSucceed()
                    updated.serverName shouldBe "My Server"
                    updated.remoteUrl shouldBe "https://example.com"

                    // fresh read via getServerSettings reflects the persisted values
                    val fresh = svc.getServerSettings().shouldSucceed()
                    fresh.serverName shouldBe "My Server"
                    fresh.remoteUrl shouldBe "https://example.com"
                }
            }
        }

        // (c) MEMBER caller → AuthError.PermissionDenied on getServerSettings
        test("getServerSettings by a MEMBER is rejected with PermissionDenied") {
            withInMemoryDatabase {
                runTest {
                    val svc =
                        AdminSettingsServiceImpl(ServerSettingsRepository(this@withInMemoryDatabase, default = RegistrationPolicy.OPEN))
                            .copyWith(principalFor("m1", UserRole.MEMBER))
                    svc.getServerSettings().shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        // (d) blank serverName → AdminError.InvalidInput
        test("updateServerSettings with a blank serverName returns InvalidInput") {
            withInMemoryDatabase {
                runTest {
                    val svc =
                        AdminSettingsServiceImpl(ServerSettingsRepository(this@withInMemoryDatabase, default = RegistrationPolicy.OPEN))
                            .copyWith(principalFor("root1", UserRole.ROOT))
                    svc
                        .updateServerSettings(AdminServerSettingsPatch(serverName = "   "))
                        .shouldFail<AdminError.InvalidInput>()
                }
            }
        }

        // (e) empty remoteUrl clears it
        test("updateServerSettings with empty remoteUrl clears the stored remote URL") {
            withInMemoryDatabase {
                runTest {
                    val repo = ServerSettingsRepository(this@withInMemoryDatabase, default = RegistrationPolicy.OPEN)
                    val svc = AdminSettingsServiceImpl(repo).copyWith(principalFor("root1", UserRole.ROOT))

                    // first set a URL
                    svc.updateServerSettings(AdminServerSettingsPatch(remoteUrl = "https://example.com")).shouldSucceed()
                    val withUrl = svc.getServerSettings().shouldSucceed()
                    withUrl.remoteUrl shouldBe "https://example.com"

                    // then clear it with an empty string
                    svc.updateServerSettings(AdminServerSettingsPatch(remoteUrl = "")).shouldSucceed()
                    val cleared = svc.getServerSettings().shouldSucceed()
                    cleared.remoteUrl.shouldBeNull()
                }
            }
        }
    })

private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
