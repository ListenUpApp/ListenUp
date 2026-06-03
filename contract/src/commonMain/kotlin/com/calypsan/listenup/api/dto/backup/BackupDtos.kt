package com.calypsan.listenup.api.dto.backup

import com.calypsan.listenup.core.BackupId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Metadata for one stored backup archive, read from its manifest. */
@Serializable
data class BackupSummary(
    val id: BackupId,
    val createdAt: Long,
    val sizeBytes: Long,
    val includesImages: Boolean,
    val schemaVersion: String,
    val appVersion: String,
    val bookCount: Int,
    val userCount: Int,
)

/** Outcome of a completed restore. */
@Serializable
data class RestoreResult(
    val restoredFrom: BackupId,
    val includedImages: Boolean,
    val schemaMigratedFrom: String,
    val schemaMigratedTo: String,
)

/** Progress events streamed during backup create and restore. */
@Serializable
sealed interface BackupEvent {
    @Serializable
    @SerialName("BackupEvent.DbSnapshotting")
    data object DbSnapshotting : BackupEvent

    @Serializable
    @SerialName("BackupEvent.ImagesCopying")
    data class ImagesCopying(
        val done: Int,
        val total: Int,
    ) : BackupEvent

    @Serializable
    @SerialName("BackupEvent.Finalizing")
    data object Finalizing : BackupEvent

    @Serializable
    @SerialName("BackupEvent.Created")
    data class Created(
        val summary: BackupSummary,
    ) : BackupEvent

    @Serializable
    @SerialName("BackupEvent.Validating")
    data object Validating : BackupEvent

    @Serializable
    @SerialName("BackupEvent.Draining")
    data object Draining : BackupEvent

    @Serializable
    @SerialName("BackupEvent.Swapping")
    data object Swapping : BackupEvent

    @Serializable
    @SerialName("BackupEvent.Migrating")
    data object Migrating : BackupEvent

    @Serializable
    @SerialName("BackupEvent.RestoreComplete")
    data class RestoreComplete(
        val includedImages: Boolean,
    ) : BackupEvent

    @Serializable
    @SerialName("BackupEvent.RolledBack")
    data class RolledBack(
        val reason: String,
    ) : BackupEvent
}
