@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class SessionServiceDeviceTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun freshDb(): Database {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            return DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}")).database
        }

        fun seedUser(
            db: Database,
            id: String,
        ) {
            transaction(db) {
                UserEntity.new(id) {
                    email = "$id@example.com"
                    emailNormalized = "$id@example.com"
                    passwordHash = "phc"
                    role = UserRoleColumn.MEMBER
                    displayName = id
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
            }
        }

        test("createSession persists DeviceInfo fields and userAgent") {
            val db = freshDb()
            seedUser(db, "u-1")
            val service =
                SessionService(db.asSqlDatabase(), RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued =
                service.createSession(
                    userId = UserId("u-1"),
                    label = "My iPhone",
                    deviceInfo =
                        DeviceInfo(
                            deviceType = "phone",
                            platform = "iOS",
                            platformVersion = "17.2",
                            clientName = "ListenUp iOS",
                            clientVersion = "1.0.0",
                            deviceName = "Simon's iPhone",
                            deviceModel = "iPhone15,2",
                        ),
                    userAgent = "ListenUp/1.0",
                )

            val row = service.listActiveFor(UserId("u-1")).single { it.id == issued.sessionId.value }
            row.platform shouldBe "iOS"
            row.device_model shouldBe "iPhone15,2"
            row.device_name shouldBe "Simon's iPhone"
            row.client_name shouldBe "ListenUp iOS"
            row.user_agent shouldBe "ListenUp/1.0"
            row.label shouldBe "My iPhone"
        }
    })
