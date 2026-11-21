package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * The production graph builds a sitting when a listen arrives. Every recorder harness defaults its sittings
 * hook to a no-op, so this is the test that would fail if the server's wiring forgot it.
 */
class SittingsWiringTest :
    FunSpec({
        test("a listen stored through the real server builds its sitting") {
            val libraryRoot = Files.createTempDirectory("listenup-sittings-wiring-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val userId =
                        publicAuthService()
                            .setupRoot(RegisterRequest("kyle@sittings-wiring.example", "x".repeat(8), "Kyle"))
                            .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                            .data.user.id.value
                    seedTestLibraryAndFolder()
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestBook("red-rising")
                    val events by application.inject<ListeningEventRepository>()
                    val sittings by application.inject<SittingRepository>()
                    val endedAt = System.currentTimeMillis() - 60_000L

                    events.upsert(
                        ListeningEventSyncPayload(
                            id = "evt-1",
                            bookId = "red-rising",
                            startPositionMs = 0L,
                            endPositionMs = 600_000L,
                            startedAt = endedAt - 600_000L,
                            endedAt = endedAt,
                            playbackSpeed = 1.0f,
                            tz = "UTC",
                            deviceLabel = null,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                        clientOpId = null,
                        userId = userId,
                    )

                    sittings.liveFor(userId, "red-rising").single().endedAt shouldBe endedAt
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
