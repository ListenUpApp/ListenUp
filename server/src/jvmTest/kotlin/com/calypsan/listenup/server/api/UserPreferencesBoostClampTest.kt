package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class UserPreferencesBoostClampTest :
    FunSpec({
        fun svcFor(sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase) =
            userPreferencesServiceScopedTo(
                createUserPreferencesService(sql),
                PrincipalProvider {
                    UserPrincipal(UserId("u1"), SessionId("s-u1"), UserRole.MEMBER)
                },
            )

        test("defaultVolumeBoostDb above the max clamps to 12f, stored and returned") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val svc = svcFor(sql)
                    val r = svc.updateMyPreferences(UpdateUserPreferencesRequest(defaultVolumeBoostDb = 99f))
                    r as AppResult.Success
                    r.data.defaultVolumeBoostDb shouldBe 12f

                    val stored = svc.getMyPreferences()
                    stored as AppResult.Success
                    stored.data.defaultVolumeBoostDb shouldBe 12f
                }
            }
        }

        test("negative defaultVolumeBoostDb clamps to 0f") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val svc = svcFor(sql)
                    val r = svc.updateMyPreferences(UpdateUserPreferencesRequest(defaultVolumeBoostDb = -5f))
                    r as AppResult.Success
                    r.data.defaultVolumeBoostDb shouldBe 0f

                    val stored = svc.getMyPreferences()
                    stored as AppResult.Success
                    stored.data.defaultVolumeBoostDb shouldBe 0f
                }
            }
        }

        test("patch with defaultVolumeBoostDb null leaves the stored value unchanged") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val svc = svcFor(sql)
                    svc.updateMyPreferences(UpdateUserPreferencesRequest(defaultVolumeBoostDb = 6f))

                    val r = svc.updateMyPreferences(UpdateUserPreferencesRequest(defaultPlaybackSpeed = 1.5f))
                    r as AppResult.Success
                    r.data.defaultVolumeBoostDb shouldBe 6f
                    r.data.defaultPlaybackSpeed shouldBe 1.5f

                    val stored = svc.getMyPreferences()
                    stored as AppResult.Success
                    stored.data.defaultVolumeBoostDb shouldBe 6f
                }
            }
        }
    })
