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
    })
