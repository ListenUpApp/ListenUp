package com.calypsan.listenup.client.features.admin.backup

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.upload.ABSUploadWorker
import com.calypsan.listenup.client.util.DocumentPickerResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

private val logger = KotlinLogging.logger {}

private const val DEFAULT_FILENAME = "backup"

/**
 * State for the ABS upload flow.
 */
sealed interface ABSUploadState {
    /** Initial state - waiting for file selection. */
    data object SelectFile : ABSUploadState

    /** File selected, ready to upload. */
    data class FileSelected(
        val filename: String,
        val sizeBytes: Long,
    ) : ABSUploadState

    /** Upload and analysis in progress. */
    data class Uploading(
        val filename: String,
        val phase: UploadPhase,
    ) : ABSUploadState

    /** Upload completed successfully. */
    data class Complete(
        val importId: String,
        val filename: String,
    ) : ABSUploadState

    /** Upload failed. */
    data class Error(
        val message: String,
        val filename: String?,
    ) : ABSUploadState
}

/**
 * Phase of the upload/analysis process.
 */
enum class UploadPhase {
    UPLOADING,
    ANALYZING,
}

/**
 * Bottom sheet for uploading an ABS backup file.
 *
 * Provides a streamlined flow:
 * 1. File selection via document picker
 * 2. Upload progress with phase indicator
 * 3. Auto-navigation to import hub on success
 *
 * @param state Current upload state
 * @param onPickFile Called when user wants to pick a file
 * @param onUpload Called when user confirms upload
 * @param onNavigateToImport Called when upload completes with import ID
 * @param onDismiss Called when sheet is dismissed
 * @param onRetry Called to retry after error
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ABSUploadSheet(
    state: ABSUploadState,
    onPickFile: () -> Unit,
    onUpload: () -> Unit,
    onNavigateToImport: (String) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Surface(
                modifier =
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(32.dp)
                        .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn() + slideInVertically { it / 4 } togetherWith
                        fadeOut() + slideOutVertically { -it / 4 }
                },
                label = "upload_state",
            ) { currentState ->
                when (currentState) {
                    is ABSUploadState.SelectFile -> {
                        SelectFileContent(onPickFile = onPickFile)
                    }

                    is ABSUploadState.FileSelected -> {
                        FileSelectedContent(
                            filename = currentState.filename,
                            sizeBytes = currentState.sizeBytes,
                            onUpload = onUpload,
                            onChangeFile = onPickFile,
                        )
                    }

                    is ABSUploadState.Uploading -> {
                        UploadingContent(
                            filename = currentState.filename,
                            phase = currentState.phase,
                        )
                    }

                    is ABSUploadState.Complete -> {
                        CompleteContent(
                            filename = currentState.filename,
                            onContinue = { onNavigateToImport(currentState.importId) },
                        )
                    }

                    is ABSUploadState.Error -> {
                        ErrorContent(
                            message = currentState.message,
                            onRetry = onRetry,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectFileContent(onPickFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Text(
            text = "Import from Audiobookshelf",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Upload your Audiobookshelf backup to migrate listening history and progress.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        // Upload icon area
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        ListenUpButton(
            text = "Select Backup File",
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Supports .audiobookshelf, .zip, .tar.gz files",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileSelectedContent(
    filename: String,
    sizeBytes: Long,
    onUpload: () -> Unit,
    onChangeFile: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Ready to Upload",
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(16.dp))

        // File info card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatFileSize(sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        ListenUpButton(
            text = "Upload & Analyze",
            onClick = onUpload,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onChangeFile,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text("Choose Different File")
        }
    }
}

@Composable
private fun UploadingContent(
    filename: String,
    phase: UploadPhase,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                when (phase) {
                    UploadPhase.UPLOADING -> "Uploading..."
                    UploadPhase.ANALYZING -> "Analyzing..."
                },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text =
                when (phase) {
                    UploadPhase.UPLOADING -> "Uploading $filename to server"
                    UploadPhase.ANALYZING -> "Analyzing backup contents and matching books"
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(32.dp))

        LinearProgressIndicator(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
        )

        Spacer(Modifier.height(16.dp))

        // Phase indicator
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PhaseIndicator(
                label = "Upload",
                isActive = phase == UploadPhase.UPLOADING,
                isComplete = phase == UploadPhase.ANALYZING,
            )
            PhaseIndicator(
                label = "Analyze",
                isActive = phase == UploadPhase.ANALYZING,
                isComplete = false,
            )
        }
    }
}

@Composable
private fun PhaseIndicator(
    label: String,
    isActive: Boolean,
    isComplete: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        when {
                            isComplete -> MaterialTheme.colorScheme.primary
                            isActive -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (isComplete) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (isActive || isComplete) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun CompleteContent(
    filename: String,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Import Ready",
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Your backup has been uploaded and analyzed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        ListenUpButton(
            text = "Continue to Import",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Upload Failed",
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        ListenUpButton(
            text = "Try Again",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text("Cancel")
        }
    }
}

/**
 * Format file size in human-readable format.
 */
@Suppress("MagicNumber")
private fun formatFileSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }

/**
 * State holder for the ABS upload sheet.
 *
 * Manages the upload flow lifecycle, coordinates with the document picker,
 * and enqueues background upload via WorkManager for background-safe operation.
 */
class ABSUploadSheetState {
    var uploadState by mutableStateOf<ABSUploadState>(ABSUploadState.SelectFile)
        private set

    /** Content URI of the selected file (kept for cache copy). */
    private var pendingUri: Uri? = null
    private var pendingFilename: String? = null
    private var pendingSize: Long = 0L

    /** UUID of the currently enqueued work request, if any. */
    var activeWorkId: UUID? by mutableStateOf(null)
        private set

    /**
     * Handle document picker result.
     *
     * The [DocumentPickerResult.Success] includes the raw content URI,
     * which is needed to copy the file to cache before background upload.
     */
    fun onDocumentSelected(result: DocumentPickerResult) {
        when (result) {
            is DocumentPickerResult.Success -> {
                pendingUri = result.uri
                pendingFilename = result.filename
                pendingSize = result.size
                uploadState =
                    ABSUploadState.FileSelected(
                        filename = result.filename,
                        sizeBytes = result.size,
                    )
            }

            is DocumentPickerResult.Error -> {
                uploadState =
                    ABSUploadState.Error(
                        message = result.message,
                        filename = null,
                    )
            }

            DocumentPickerResult.Cancelled -> {
                // Do nothing, keep current state
            }
        }
    }

    /**
     * Copy the selected file to cache and enqueue the background upload worker.
     *
     * The file copy **must** happen in the foreground because SAF content URIs
     * may not survive app backgrounding.
     *
     * @param context Android context for cache dir and WorkManager
     * @return The work request UUID, or null if no file is pending
     */
    fun enqueueUpload(context: Context): UUID? {
        val uri = pendingUri ?: return null
        val filename = pendingFilename ?: return null

        uploadState = ABSUploadState.Uploading(filename, UploadPhase.UPLOADING)

        return try {
            // Copy content URI to cache file (must happen in foreground)
            val cacheFile = copyToCache(context, uri, filename)

            // Enqueue background worker
            val workId =
                ABSUploadWorker.enqueue(
                    context = context,
                    cacheFilePath = cacheFile.absolutePath,
                    filename = filename,
                    fileSize = cacheFile.length(),
                )

            activeWorkId = workId
            logger.info { "Enqueued ABS upload worker: workId=$workId, file=${cacheFile.absolutePath}" }
            workId
        } catch (e: Exception) {
            logger.error(e) { "Failed to enqueue upload" }
            uploadState =
                ABSUploadState.Error(
                    message = e.message ?: "Failed to prepare upload",
                    filename = filename,
                )
            null
        }
    }

    /**
     * Observe [WorkInfo] and update upload state accordingly.
     *
     * Call this from a `LaunchedEffect` keyed on [activeWorkId].
     */
    fun observeWorkInfo(workInfo: WorkInfo?) {
        if (workInfo == null) return
        val filename = pendingFilename ?: DEFAULT_FILENAME

        when (workInfo.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            -> {
                uploadState = ABSUploadState.Uploading(filename, UploadPhase.UPLOADING)
            }

            WorkInfo.State.RUNNING -> {
                val phase = workInfo.progress.getString("phase")
                val uploadPhase = if (phase == "analyzing") UploadPhase.ANALYZING else UploadPhase.UPLOADING
                uploadState = ABSUploadState.Uploading(filename, uploadPhase)
            }

            WorkInfo.State.SUCCEEDED -> {
                val importId = workInfo.outputData.getString(ABSUploadWorker.KEY_IMPORT_ID) ?: ""
                uploadState = ABSUploadState.Complete(importId, filename)
                activeWorkId = null
            }

            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(ABSUploadWorker.KEY_ERROR) ?: "Upload failed"
                uploadState = ABSUploadState.Error(message = error, filename = filename)
                activeWorkId = null
            }

            WorkInfo.State.CANCELLED -> {
                uploadState = ABSUploadState.Error(message = "Upload cancelled", filename = filename)
                activeWorkId = null
            }
        }
    }

    /**
     * Get a [Flow] of [WorkInfo] for the active work request.
     */
    fun getWorkInfoFlow(context: Context): Flow<WorkInfo?>? {
        val workId = activeWorkId ?: return null
        return WorkManager
            .getInstance(context)
            .getWorkInfoByIdFlow(workId)
    }

    /**
     * Check WorkManager for any in-flight ABSUploadWorker job.
     * Call this on screen startup to restore activeWorkId after app restart.
     * If a running/enqueued job is found, sets activeWorkId so the
     * existing LaunchedEffect(uploadSheetState.activeWorkId) can observe it.
     */
    fun restoreActiveWorkIfRunning(context: Context) {
        if (activeWorkId != null) return
        val infos =
            WorkManager
                .getInstance(context)
                .getWorkInfosByTag(ABSUploadWorker.WORK_TAG)
                .get()
        val running =
            infos.firstOrNull { info ->
                info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
            }
        if (running != null) {
            activeWorkId = running.id
            if (pendingFilename == null) pendingFilename = DEFAULT_FILENAME
            uploadState = ABSUploadState.Uploading(pendingFilename ?: DEFAULT_FILENAME, UploadPhase.UPLOADING)
        }
    }

    /**
     * Reset to file selection state.
     */
    fun retry() {
        pendingUri = null
        pendingFilename = null
        pendingSize = 0L
        activeWorkId = null
        uploadState = ABSUploadState.SelectFile
    }

    /**
     * Reset state completely.
     */
    fun reset() {
        pendingUri = null
        pendingFilename = null
        pendingSize = 0L
        activeWorkId = null
        uploadState = ABSUploadState.SelectFile
    }

    /**
     * Copy a content URI to the app's cache directory.
     *
     * This must be called in the foreground because SAF URIs may not persist
     * after the app is backgrounded.
     */
    private fun copyToCache(
        context: Context,
        uri: Uri,
        filename: String,
    ): File {
        val cacheDir = File(context.filesDir, "abs_uploads")
        cacheDir.mkdirs()

        val cacheFile = File(cacheDir, "${System.currentTimeMillis()}_$filename")

        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Could not open input stream for URI: $uri")

        logger.debug { "Copied ${cacheFile.length()} bytes to cache: ${cacheFile.absolutePath}" }
        return cacheFile
    }
}

/**
 * Remember state for the ABS upload sheet.
 */
@Composable
fun rememberABSUploadSheetState(): ABSUploadSheetState = remember { ABSUploadSheetState() }
