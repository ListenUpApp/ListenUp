@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.testing.migratedTestDatabase
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class SessionServiceDeviceTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun freshDb() = migratedTestDatabase().db

        test("createSession persists DeviceInfo fields and userAgent") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val service =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

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

        // A session records the app version it signed in with and never updated it: on 2026-09-12
        // Chris's row still said 0.8.6 a month and several releases later, and nothing on the server
        // could say what he actually ran. Each refresh now reports the running version.
        test("a refresh records the app version the device is running now") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val service = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val issued =
                service.createSession(
                    userId = UserId("u-1"),
                    label = null,
                    deviceInfo = DeviceInfo(platform = "Android", clientVersion = "0.8.6"),
                    userAgent = null,
                )

            service.rotate(issued.refreshToken, clientVersion = "0.9.5")

            service.listActiveFor(UserId("u-1")).single().client_version shouldBe "0.9.5"
        }

        test("a refresh from a client that does not report its version keeps the recorded one") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val service = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val issued =
                service.createSession(
                    userId = UserId("u-1"),
                    label = null,
                    deviceInfo = DeviceInfo(platform = "Android", clientVersion = "0.8.6"),
                    userAgent = null,
                )

            service.rotate(issued.refreshToken, clientVersion = null)

            service.listActiveFor(UserId("u-1")).single().client_version shouldBe "0.8.6"
        }
    })
