package com.calypsan.listenup.client.data.local.db

import androidx.room3.Entity

/**
 * A cover or photo that was saved on this device but has not reached the server.
 *
 * One row per image: the pair ([kind], [entityId]) names it, and the bytes are not stored here, they
 * are the committed local file for that entity, read when the upload is retried. So a second save
 * for the same image replaces the row and the retry uploads the latest file.
 *
 * @property kind Which image: a `PendingImageKind` name (`BookCover`, `SeriesCover`, `ContributorImage`).
 * @property entityId The book, series or contributor the image belongs to.
 * @property ownerUserId The signed-in user who saved it; another user's rows are dropped, not uploaded.
 * @property enqueuedAt When this save was recorded. Doubles as the row's version: a retry that finishes
 *   deletes the row only if it still has the `enqueuedAt` it started with, so a save made during the
 *   upload is not lost.
 * @property attempts Retries that ended in a retryable failure since this save; diagnostic only.
 */
@Entity(tableName = "pending_image_upload", primaryKeys = ["kind", "entityId"])
internal data class PendingImageUploadEntity(
    val kind: String,
    val entityId: String,
    val ownerUserId: String,
    val enqueuedAt: Long,
    val attempts: Int = 0,
)
