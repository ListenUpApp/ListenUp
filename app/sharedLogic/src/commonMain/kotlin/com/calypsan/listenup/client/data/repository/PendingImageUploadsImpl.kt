package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.core.image.photoVersionOf
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PendingImageUploadDao
import com.calypsan.listenup.client.data.local.db.PendingImageUploadEntity
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.domain.repository.PendingImageUploads
import com.calypsan.listenup.client.domain.repository.isWorthRetryingUpload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.currentEpochMilliseconds
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private val logger = KotlinLogging.logger {}

/** What one pass over the pending uploads achieved. */
internal data class ImageUploadDrain(
    /** Images that reached the server and were forgotten. */
    val uploaded: Int,
    /** Images still waiting: the failure was transient, so the next pass tries again. */
    val stillPending: Int,
    /** Images the server refused; dropped by this pass and reported here once. */
    val refused: List<ImageUploadRefusal>,
)

/** One image the server refused to take: the retry gave up on it for good. */
internal data class ImageUploadRefusal(
    val kind: PendingImageKind,
    val entityId: String,
    val error: AppError,
)

/**
 * Remembers covers and photos that were saved on this device but did not reach the server, and uploads
 * them once the server answers. See [PendingImageUploads] for the contract.
 *
 * Lives in the data layer beside the other stores that own a DAO. A row names an image by
 * (kind, entity id); the bytes are the committed local file for that entity, read when an upload is
 * tried, so the latest save is what goes. [drain] is single-flight: it holds a mutex, so the several
 * triggers (app start, reconnect, network back) collapse into one pass at a time.
 *
 * The web client never records: it keeps no image bytes after a save, so a failed upload there is
 * reported to the user only.
 *
 * @param now clock for the record time; injected so tests control it.
 */
internal class PendingImageUploadsImpl(
    private val dao: PendingImageUploadDao,
    private val imageStorage: ImageStorage,
    private val imageApi: ImageApiContract,
    private val authSession: AuthSession,
    private val contributorDao: ContributorDao,
    private val now: () -> Long = ::currentEpochMilliseconds,
) : PendingImageUploads {
    private val drainMutex = Mutex()

    override suspend fun record(
        kind: PendingImageKind,
        entityId: String,
    ): Boolean {
        if (!imageStorage.keepsImageFiles) return false
        val owner = authSession.getUserId()
        if (owner == null) {
            logger.warn { "No signed-in user; not recording a pending ${kind.name} upload for $entityId" }
            return false
        }
        dao.record(kind = kind.name, entityId = entityId, ownerUserId = owner, now = now())
        return true
    }

    override suspend fun forget(
        kind: PendingImageKind,
        entityId: String,
    ) = dao.delete(kind.name, entityId)

    /** Whether ([kind], [entityId]) has an upload waiting. */
    suspend fun isPending(
        kind: PendingImageKind,
        entityId: String,
    ): Boolean = dao.exists(kind.name, entityId)

    /**
     * Try every pending upload for the signed-in user once.
     *
     * A row is dropped when the file is gone, when the server refuses the image, or when it was
     * recorded by a different user; a transient failure leaves it for the next pass, and a network-level
     * failure ends this pass early so a down server is asked once, not once per image. A row re-recorded
     * while its upload was in flight is kept: it is a newer save the finished upload did not carry.
     */
    suspend fun drain(): ImageUploadDrain =
        drainMutex.withLock {
            val owner = (authSession.authState.value as? AuthState.Authenticated)?.run { userId.value }
            if (owner == null) {
                return@withLock ImageUploadDrain(uploaded = 0, stillPending = 0, refused = emptyList())
            }
            dao.deleteAllExcept(owner)

            var uploaded = 0
            var stillPending = 0
            val refused = mutableListOf<ImageUploadRefusal>()
            val rows = dao.pendingFor(owner)
            for ((index, row) in rows.withIndex()) {
                when (val outcome = uploadOne(row)) {
                    UploadOutcome.Uploaded -> {
                        uploaded++
                    }

                    UploadOutcome.Dropped -> {
                        // Nothing was sent and nothing is owed: not counted.
                    }

                    is UploadOutcome.Refused -> {
                        refused += outcome.refusal
                    }

                    is UploadOutcome.Failed -> {
                        stillPending++
                        if (outcome.error.isUnreachable()) {
                            stillPending += rows.size - index - 1
                            break
                        }
                    }
                }
            }
            ImageUploadDrain(uploaded = uploaded, stillPending = stillPending, refused = refused)
        }

    private suspend fun uploadOne(row: PendingImageUploadEntity): UploadOutcome {
        val kind = PendingImageKind.entries.firstOrNull { it.name == row.kind }
        val bytes = kind?.let { readLocalImage(it, row.entityId) }
        if (kind == null || bytes == null) {
            // Nothing to send: an unknown kind from a newer build, or a file removed since the save.
            dao.deleteIfUnchanged(row.kind, row.entityId, row.enqueuedAt)
            return UploadOutcome.Dropped
        }
        val result = upload(kind, row.entityId, bytes)
        return when (result) {
            is AppResult.Success -> {
                dao.deleteIfUnchanged(row.kind, row.entityId, row.enqueuedAt)
                UploadOutcome.Uploaded
            }

            is AppResult.Failure -> {
                if (result.error.isWorthRetryingUpload()) {
                    dao.recordAttempt(row.kind, row.entityId, row.enqueuedAt)
                    UploadOutcome.Failed(result.error)
                } else {
                    logger.warn { "Server refused the ${row.kind} upload for ${row.entityId}: ${result.error.code}" }
                    dao.deleteIfUnchanged(row.kind, row.entityId, row.enqueuedAt)
                    UploadOutcome.Refused(ImageUploadRefusal(kind, row.entityId, result.error))
                }
            }
        }
    }

    private suspend fun upload(
        kind: PendingImageKind,
        entityId: String,
        bytes: ByteArray,
    ): AppResult<*> =
        when (kind) {
            PendingImageKind.BookCover -> imageApi.uploadBookCover(entityId, bytes, "$entityId.jpg")
            PendingImageKind.SeriesCover -> imageApi.uploadSeriesCover(entityId, bytes, "$entityId.jpg")
            PendingImageKind.ContributorImage -> {
                imageApi.uploadContributorImage(entityId, bytes, "$entityId.jpg", frameFor(entityId, bytes))
            }
        }

    /**
     * The frame this device stamped for exactly these bytes, or null. A frame and its photo therefore always reach the
     * server in one request and can never land out of order; a frame stamped for other bytes never rides along.
     */
    private suspend fun frameFor(
        contributorId: String,
        bytes: ByteArray,
    ): ImageFrame? =
        contributorDao
            .getById(contributorId)
            ?.imageFrame
            ?.takeIf { it.imageVersion == photoVersionOf(bytes) }
            ?.frame

    /** The committed local file for the image, or null when there is none. Read off the main dispatcher. */
    private suspend fun readLocalImage(
        kind: PendingImageKind,
        entityId: String,
    ): ByteArray? {
        val path =
            when (kind) {
                PendingImageKind.BookCover -> {
                    val bookId = BookId(entityId)
                    if (imageStorage.exists(bookId)) imageStorage.getCoverPath(bookId) else null
                }

                PendingImageKind.SeriesCover -> {
                    if (imageStorage.seriesCoverExists(entityId)) imageStorage.getSeriesCoverPath(entityId) else null
                }

                PendingImageKind.ContributorImage -> {
                    if (imageStorage.contributorImageExists(entityId)) {
                        imageStorage.getContributorImagePath(entityId)
                    } else {
                        null
                    }
                }
            } ?: return null
        return withContext(IODispatcher) {
            SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }
        }
    }

    private sealed interface UploadOutcome {
        data object Uploaded : UploadOutcome

        data object Dropped : UploadOutcome

        /** The upload failed for a reason that may pass ([error]); the image stays queued for the next pass. */
        data class Failed(
            val error: AppError,
        ) : UploadOutcome

        /** The server refused the image for good ([refusal]); it is dropped from the queue and reported. */
        data class Refused(
            val refusal: ImageUploadRefusal,
        ) : UploadOutcome
    }
}

/** A failure that says the server could not be reached at all, so the remaining images would fail the same way. */
private fun AppError.isUnreachable(): Boolean =
    this is TransportError.NetworkUnavailable || this is TransportError.Timeout
