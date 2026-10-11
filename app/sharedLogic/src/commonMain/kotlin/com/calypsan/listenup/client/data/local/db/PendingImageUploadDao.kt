package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Query

/** DAO for [PendingImageUploadEntity]; the retry logic lives in `PendingImageUploadsImpl`. */
@Dao
internal interface PendingImageUploadDao {
    /**
     * Record an image as waiting to upload. A row already there is replaced in place and its
     * `enqueuedAt` moves strictly forward, even within one millisecond, so a retry holding the old
     * value can tell the image was saved again.
     */
    @Query(
        "INSERT INTO pending_image_upload (kind, entityId, ownerUserId, enqueuedAt, attempts) " +
            "VALUES (:kind, :entityId, :ownerUserId, :now, 0) " +
            "ON CONFLICT(kind, entityId) DO UPDATE SET ownerUserId = excluded.ownerUserId, " +
            "enqueuedAt = MAX(excluded.enqueuedAt, pending_image_upload.enqueuedAt + 1), attempts = 0",
    )
    suspend fun record(
        kind: String,
        entityId: String,
        ownerUserId: String,
        now: Long,
    )

    @Query("SELECT * FROM pending_image_upload WHERE ownerUserId = :ownerUserId ORDER BY enqueuedAt ASC")
    suspend fun pendingFor(ownerUserId: String): List<PendingImageUploadEntity>

    @Query("SELECT * FROM pending_image_upload WHERE kind = :kind AND entityId = :entityId")
    suspend fun get(
        kind: String,
        entityId: String,
    ): PendingImageUploadEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM pending_image_upload WHERE kind = :kind AND entityId = :entityId)")
    suspend fun exists(
        kind: String,
        entityId: String,
    ): Boolean

    /** Drop the row only if it is still the save the caller saw; returns how many rows went. */
    @Query("DELETE FROM pending_image_upload WHERE kind = :kind AND entityId = :entityId AND enqueuedAt = :enqueuedAt")
    suspend fun deleteIfUnchanged(
        kind: String,
        entityId: String,
        enqueuedAt: Long,
    ): Int

    @Query("DELETE FROM pending_image_upload WHERE kind = :kind AND entityId = :entityId")
    suspend fun delete(
        kind: String,
        entityId: String,
    )

    @Query(
        "UPDATE pending_image_upload SET attempts = attempts + 1 " +
            "WHERE kind = :kind AND entityId = :entityId AND enqueuedAt = :enqueuedAt",
    )
    suspend fun recordAttempt(
        kind: String,
        entityId: String,
        enqueuedAt: Long,
    )

    @Query("DELETE FROM pending_image_upload WHERE ownerUserId != :ownerUserId")
    suspend fun deleteAllExcept(ownerUserId: String)

    @Query("DELETE FROM pending_image_upload")
    suspend fun deleteAll()
}
