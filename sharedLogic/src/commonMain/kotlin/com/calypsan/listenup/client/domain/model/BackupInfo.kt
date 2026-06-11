package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.core.Timestamp

/**
 * Domain model for a stored backup archive, projected from the server's
 * [BackupSummary]. Backups are filesystem-truth read from each archive's
 * manifest, so there is no client-side path or checksum.
 */
data class BackupInfo(
    val id: String,
    val size: Long,
    val createdAt: Timestamp,
) {
    /**
     * Human-readable size string.
     */
    val sizeFormatted: String
        get() =
            when {
                size < 1024 -> {
                    "$size B"
                }

                size < 1024 * 1024 -> {
                    "${size / 1024} KB"
                }

                size < 1024 * 1024 * 1024 -> {
                    val mb = size / (1024.0 * 1024.0)
                    "${(mb * 10).toLong() / 10.0} MB"
                }

                else -> {
                    val gb = size / (1024.0 * 1024.0 * 1024.0)
                    "${(gb * 100).toLong() / 100.0} GB"
                }
            }
}

/** Projects a server [BackupSummary] into the UI-facing [BackupInfo]. */
fun BackupSummary.toDomain(): BackupInfo =
    BackupInfo(
        id = id.value,
        size = sizeBytes,
        createdAt = Timestamp(createdAt),
    )
