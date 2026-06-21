package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class UserPreferencesServiceImplTest :
    FunSpec({
        test("getMyPreferences with no row returns defaults") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val r =
                        userPreferencesServiceScopedTo(
                            createUserPreferencesService(sql),
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s-u1"), UserRole.MEMBER)
                            },
                        ).getMyPreferences()
                    r as AppResult.Success
                    r.data shouldBe UserPreferencesDto(1.0f, 30, 10, null, false)
                }
            }
        }

        test("updateMyPreferences merges only non-null fields") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val svc =
                        userPreferencesServiceScopedTo(
                            createUserPreferencesService(sql),
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s-u1"), UserRole.MEMBER)
                            },
                        )
                    svc.updateMyPreferences(UpdateUserPreferencesRequest(defaultPlaybackSpeed = 1.5f))
                    val afterSpeed = svc.getMyPreferences()
                    afterSpeed as AppResult.Success
                    afterSpeed.data shouldBe UserPreferencesDto(1.5f, 30, 10, null, false)

                    svc.updateMyPreferences(UpdateUserPreferencesRequest(defaultSleepTimerMin = 20))
                    val afterSleep = svc.getMyPreferences()
                    afterSleep as AppResult.Success
                    afterSleep.data shouldBe UserPreferencesDto(1.5f, 30, 10, 20, false)
                }
            }
        }

        test("out-of-range values are clamped") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val svc =
                        userPreferencesServiceScopedTo(
                            createUserPreferencesService(sql),
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s-u1"), UserRole.MEMBER)
                            },
                        )
                    val r =
                        svc.updateMyPreferences(
                            UpdateUserPreferencesRequest(
                                defaultPlaybackSpeed = 9.0f,
                                defaultSkipForwardSec = 9999,
                            ),
                        )
                    r as AppResult.Success
                    r.data.defaultPlaybackSpeed shouldBe 4.0f
                    r.data.defaultSkipForwardSec shouldBe 300
                }
            }
        }

        test("unauthenticated call is denied") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val r = createUserPreferencesService(sql).getMyPreferences()
                    val failure = r.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })
