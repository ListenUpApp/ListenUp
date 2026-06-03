package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Failures from the backup/restore domain. See the backup-restore design spec. */
@Serializable
sealed interface BackupError : AppError {
    @Serializable
    @SerialName("BackupError.SnapshotFailed")
    data class SnapshotFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BackupError {
        override val message: String = "Failed to create the backup. Check server disk space and try again."
        override val code: String = "BACKUP_SNAPSHOT_FAILED"
        override val isRetryable: Boolean = false
    }

    @Serializable
    @SerialName("BackupError.CorruptArchive")
    data class CorruptArchive(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BackupError {
        override val message: String = "This backup file is incomplete or corrupted and can't be restored."
        override val code: String = "BACKUP_CORRUPT_ARCHIVE"
        override val isRetryable: Boolean = false
    }

    @Serializable
    @SerialName("BackupError.IncompatibleSchema")
    data class IncompatibleSchema(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BackupError {
        override val message: String = "This backup is from a newer version of ListenUp. Update the server first."
        override val code: String = "BACKUP_INCOMPATIBLE_SCHEMA"
        override val isRetryable: Boolean = false
    }

    @Serializable
    @SerialName("BackupError.BackupNotFound")
    data class BackupNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BackupError {
        override val message: String = "That backup no longer exists."
        override val code: String = "BACKUP_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    @Serializable
    @SerialName("BackupError.RestoreInProgress")
    data class RestoreInProgress(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BackupError {
        override val message: String = "A restore is already running. Try again in a moment."
        override val code: String = "BACKUP_RESTORE_IN_PROGRESS"
        override val isRetryable: Boolean = true
    }

    @Serializable
    @SerialName("BackupError.RestoreFailed")
    data class RestoreFailed(
        val rolledBack: Boolean,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BackupError {
        override val message: String = "The restore failed. Your previous data has been kept."
        override val code: String = "BACKUP_RESTORE_FAILED"
        override val isRetryable: Boolean = false
    }
}
