package com.calypsan.listenup.client.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.calypsan.listenup.client.MainActivity
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.shortcuts.ShortcutActions
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.UUID

private val logger = KotlinLogging.logger {}

private const val CHANNEL_ID = "abs_upload"
private const val CHANNEL_NAME = "ABS Import Upload"
private const val NOTIFICATION_ID = 9001
private const val RESULT_CHANNEL_ID = "abs_import_complete"
private const val RESULT_CHANNEL_NAME = "ABS Import Results"
private const val RESULT_NOTIFICATION_ID = 9002
private const val MAX_RUN_ATTEMPTS = 3
private const val NOTIFICATION_TITLE_FAILURE = "Backup import failed"
private const val NOTIFICATION_TEXT_FAILURE =
    "The Audiobookshelf backup could not be processed. Please try again."

/**
 * Background worker that uploads an Audiobookshelf backup and creates an import.
 *
 * Runs as a foreground service with [FOREGROUND_SERVICE_TYPE_DATA_SYNC][ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]
 * so the upload survives app backgrounding.
 *
 * Input data:
 * - [KEY_CACHE_FILE_PATH] — absolute path to the cached backup file
 * - [KEY_FILENAME] — original display filename
 * - [KEY_FILE_SIZE] — file size in bytes
 *
 * Output data:
 * - [KEY_IMPORT_ID] — the created import ID on success
 */
class ABSUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val backupApi: BackupApiContract,
    private val absImportApi: ABSImportApiContract,
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_CACHE_FILE_PATH = "cache_file_path"
        const val KEY_FILENAME = "filename"
        const val KEY_FILE_SIZE = "file_size"
        const val KEY_IMPORT_ID = "import_id"
        const val KEY_ERROR = "error"
        const val WORK_TAG = "abs_upload_worker"

        /**
         * Enqueue an upload work request.
         *
         * @param context Android context
         * @param cacheFilePath Absolute path to the cached backup file
         * @param filename Original display filename
         * @param fileSize File size in bytes
         * @return The work request UUID for observation
         */
        fun enqueue(
            context: Context,
            cacheFilePath: String,
            filename: String,
            fileSize: Long,
        ): UUID {
            val data =
                workDataOf(
                    KEY_CACHE_FILE_PATH to cacheFilePath,
                    KEY_FILENAME to filename,
                    KEY_FILE_SIZE to fileSize,
                )

            val request =
                OneTimeWorkRequestBuilder<ABSUploadWorker>()
                    .setInputData(data)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(WORK_TAG)
                    .build()

            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }

    override suspend fun doWork(): Result {
        val cacheFilePath =
            inputData.getString(KEY_CACHE_FILE_PATH)
                ?: return Result.failure(workDataOf(KEY_ERROR to "Missing cache file path"))
        val filename =
            inputData.getString(KEY_FILENAME)
                ?: return Result.failure(workDataOf(KEY_ERROR to "Missing filename"))

        val cacheFile = File(cacheFilePath)

        // Hold a partial wakelock for the duration of the upload + import creation.
        // CoroutineWorker does NOT hold a wakelock automatically, so without this the CPU
        // can enter doze mode mid-upload and kill the network connection even though the
        // foreground service is still running.
        val wakeLock =
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ListenUp:ABSUpload")
                .apply { acquire(15 * 60 * 1000L) } // 15 min max

        // Track result so finally can skip cleanup on retry (file needed for next attempt)
        var workerResult: Result = Result.failure(workDataOf(KEY_ERROR to "Unknown error"))
        return try {
            setForeground(createForegroundInfo())

            if (!cacheFile.exists()) {
                logger.error { "Cache file does not exist: $cacheFilePath" }
                workerResult = Result.failure(workDataOf(KEY_ERROR to "Cache file not found"))
                return workerResult
            }

            logger.info { "Starting ABS upload: filename=$filename, size=${cacheFile.length()}" }

            // Upload the backup file
            val fileSource = CachedFileSource(cacheFile, filename)
            val uploadResult = backupApi.uploadABSBackup(fileSource)
            if (uploadResult is AppResult.Failure) {
                val error = uploadResult.message
                logger.error { "ABS upload failed: $error" }
                workerResult = retryOrFail(error)
                if (workerResult is Result.Failure) {
                    showFailureNotification()
                }
                return workerResult
            }
            val uploadedPath = (uploadResult as AppResult.Success).data.path
            logger.info { "Upload complete, server path: $uploadedPath" }

            // Signal to the UI that upload is done and analysis is starting
            setProgress(workDataOf("phase" to "analyzing"))

            // Create the import from the uploaded file
            workerResult =
                when (val importResult = absImportApi.createImportFromPath(uploadedPath, filename)) {
                    is AppResult.Success -> {
                        val importId = importResult.data.id
                        logger.info { "Import created: id=$importId" }
                        showResultNotification(
                            title = "Backup ready to review",
                            text = "Your Audiobookshelf backup has been analysed and is ready to map.",
                            iconRes = android.R.drawable.stat_sys_upload_done,
                            importId = importId,
                        )
                        Result.success(workDataOf(KEY_IMPORT_ID to importId))
                    }

                    is AppResult.Failure -> {
                        val error = importResult.message
                        logger.error { "Import creation failed: $error" }
                        val result = retryOrFail(error)
                        if (result is Result.Failure) {
                            showFailureNotification()
                        }
                        result
                    }
                }
            workerResult
        } catch (e: Exception) {
            logger.error(e) { "ABS upload failed" }
            workerResult = retryOrFail(e.message ?: "Upload failed")
            if (workerResult is Result.Failure) {
                showFailureNotification()
            }
            workerResult
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            // Only delete the staged file on final outcomes — keep it for retry attempts
            if (workerResult !is Result.Retry && cacheFile.exists()) {
                val deleted = cacheFile.delete()
                logger.debug { "Staged file cleanup: deleted=$deleted, path=$cacheFilePath" }
            }
        }
    }

    private fun retryOrFail(errorMessage: String): Result =
        if (runAttemptCount < MAX_RUN_ATTEMPTS) {
            logger.info { "Retrying upload (attempt ${runAttemptCount + 1}/$MAX_RUN_ATTEMPTS)" }
            Result.retry()
        } else {
            Result.failure(workDataOf(KEY_ERROR to errorMessage))
        }

    private fun showFailureNotification() {
        showResultNotification(
            title = NOTIFICATION_TITLE_FAILURE,
            text = NOTIFICATION_TEXT_FAILURE,
            iconRes = android.R.drawable.stat_notify_error,
        )
    }

    @Suppress("LongMethod")
    private fun showResultNotification(
        title: String,
        text: String,
        iconRes: Int,
        importId: String? = null,
    ) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    RESULT_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            notificationManager.createNotificationChannel(channel)
        }

        val intent =
            if (importId != null) {
                Intent(applicationContext, MainActivity::class.java).apply {
                    action = ShortcutActions.NAVIGATE_TO_ABS_IMPORT
                    putExtra(ShortcutActions.EXTRA_IMPORT_ID, importId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            } else {
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }

        val pendingIntent =
            PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Cancel the ongoing upload notification
        notificationManager.cancel(NOTIFICATION_ID)

        val notification =
            NotificationCompat
                .Builder(applicationContext, RESULT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(iconRes)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required for Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                )
            notificationManager.createNotificationChannel(channel)
        }

        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("Uploading ABS Backup")
                .setContentText("Importing Audiobookshelf backup…")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
