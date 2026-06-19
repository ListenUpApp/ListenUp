package com.calypsan.listenup.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BackupRoutePathsTest :
    FunSpec({
        test("downloadFor builds the concrete download path for a backup id") {
            BackupRoutePaths.downloadFor("backup-2026-06-18T20-36-30Z") shouldBe
                "/api/v1/admin/backups/backup-2026-06-18T20-36-30Z/download"
        }

        test("downloadFor fills the {id} slot of DOWNLOAD_TEMPLATE") {
            val id = "bk-1"
            BackupRoutePaths.downloadFor(id) shouldBe BackupRoutePaths.DOWNLOAD_TEMPLATE.replace("{id}", id)
        }
    })
