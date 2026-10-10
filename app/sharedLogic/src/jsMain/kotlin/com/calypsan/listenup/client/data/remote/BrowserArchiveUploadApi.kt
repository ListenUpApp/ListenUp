package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.BackupRoutePaths
import com.calypsan.listenup.api.ImportRoutePaths
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.client.core.BlobFileSource
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.appJson
import kotlinx.serialization.KSerializer

/** A large archive over a slow LAN can take several minutes to upload; the Ktor path allows the same. */
private const val ARCHIVE_TRANSFER_TIMEOUT_MS = 10 * 60 * 1_000

/**
 * The browser's [ArchiveUploadApiContract]: a picked file goes through [XhrUploadTransport], anything
 * else through the shared Ktor [ArchiveUploadApi].
 *
 * Ktor's JS engine copies a request body into memory whole, which a backup with its images or an
 * Audiobookshelf export does not survive. The request is the one the Ktor path sends: the same
 * endpoint and part name, the archive as the form's only part. A source that is not a
 * [BlobFileSource] still takes the Ktor path, so nothing handed to this API is refused for its type.
 */
internal class BrowserArchiveUploadApi(
    private val ktor: ArchiveUploadApiContract,
    private val transport: XhrUploadTransport,
) : ArchiveUploadApiContract {
    override suspend fun uploadBackup(source: FileSource): AppResult<BackupSummary> =
        if (source is BlobFileSource) {
            post(BackupRoutePaths.UPLOAD, partName = "backup", source = source, BackupSummary.serializer())
        } else {
            ktor.uploadBackup(source)
        }

    override suspend fun uploadAbsBackup(source: FileSource): AppResult<ImportSummary> =
        if (source is BlobFileSource) {
            post(ImportRoutePaths.ABS_UPLOAD, partName = "file", source = source, ImportSummary.serializer())
        } else {
            ktor.uploadAbsBackup(source)
        }

    private suspend fun <T> post(
        path: String,
        partName: String,
        source: BlobFileSource,
        response: KSerializer<T>,
    ): AppResult<T> =
        transport
            .postFile(
                path = path,
                partName = partName,
                file = source.file,
                filename = source.filename,
                timeoutMs = ARCHIVE_TRANSFER_TIMEOUT_MS,
            ).flatMap { body -> suspendRunCatching { appJson.decodeFromString(response, body) } }
}
