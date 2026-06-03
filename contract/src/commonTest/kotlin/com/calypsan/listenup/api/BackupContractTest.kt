package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BackupId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BackupContractTest :
    FunSpec({
        test("BackupSummary round-trips through contractJson") {
            val s =
                BackupSummary(
                    id = BackupId("backup-2026-06-02T18-30-00Z"),
                    createdAt = 1_717_352_400_000L,
                    sizeBytes = 1_234_567L,
                    includesImages = true,
                    schemaVersion = "29",
                    appVersion = "0.1.0",
                    bookCount = 143,
                    userCount = 5,
                )
            contractJson.decodeFromString<BackupSummary>(contractJson.encodeToString(s)) shouldBe s
        }

        test("RestoreResult round-trips") {
            val r =
                RestoreResult(
                    restoredFrom = BackupId("b1"),
                    includedImages = false,
                    schemaMigratedFrom = "27",
                    schemaMigratedTo = "29",
                )
            contractJson.decodeFromString<RestoreResult>(contractJson.encodeToString(r)) shouldBe r
        }

        test("BackupError subtypes round-trip polymorphically as AppError") {
            val err: AppResult<Unit> = AppResult.Failure(BackupError.RestoreFailed(rolledBack = true))
            contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(err)) shouldBe err
        }

        test("BackupEvent variants round-trip polymorphically") {
            val events =
                listOf<BackupEvent>(
                    BackupEvent.DbSnapshotting,
                    BackupEvent.ImagesCopying(done = 3, total = 10),
                    BackupEvent.Created(
                        BackupSummary(BackupId("b2"), 1L, 2L, true, "29", "0.1.0", 1, 1),
                    ),
                    BackupEvent.Swapping,
                    BackupEvent.RestoreComplete(includedImages = true),
                    BackupEvent.RolledBack(reason = "disk full"),
                )
            events.forEach { e ->
                contractJson.decodeFromString<BackupEvent>(contractJson.encodeToString(e)) shouldBe e
            }
        }
    })
