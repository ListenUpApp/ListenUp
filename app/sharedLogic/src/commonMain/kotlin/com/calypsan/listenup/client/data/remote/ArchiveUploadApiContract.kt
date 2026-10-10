package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FileSource

/**
 * Sends an admin's archive file to the server: a ListenUp backup to restore from, or an
 * Audiobookshelf backup to import.
 *
 * Split from the repositories that use it so the browser can bind its own: Ktor's JS engine copies a
 * whole request body into memory, which a multi-hundred-megabyte archive does not survive, so the
 * browser sends a picked file through XMLHttpRequest instead (`BrowserArchiveUploadApi`).
 */
internal interface ArchiveUploadApiContract {
    /** Uploads a `.listenup.zip` backup and answers its validated summary. */
    suspend fun uploadBackup(source: FileSource): AppResult<BackupSummary>

    /** Uploads an Audiobookshelf backup and answers the import it started. */
    suspend fun uploadAbsBackup(source: FileSource): AppResult<ImportSummary>
}
