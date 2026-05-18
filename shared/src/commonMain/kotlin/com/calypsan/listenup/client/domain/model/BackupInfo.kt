package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.Timestamp

/**
 * Domain model for a backup file.
 */
data class BackupInfo(
    val id: String,
    val path: String,
    val size: Long,
    val createdAt: Timestamp,
    val checksum: String? = null,
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

/**
 * Validation result for a backup.
 */
data class BackupValidation(
    val valid: Boolean,
    val version: String?,
    val serverName: String?,
    val entityCounts: Map<String, Int>,
    val errors: List<String>,
    val warnings: List<String>,
)
