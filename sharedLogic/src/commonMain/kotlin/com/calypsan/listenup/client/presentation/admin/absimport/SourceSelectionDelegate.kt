package com.calypsan.listenup.client.presentation.admin.absimport

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.presentation.admin.ABSImportStep
import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import com.calypsan.listenup.client.presentation.admin.ABSSourceType
import com.calypsan.listenup.client.presentation.admin.SelectedLocalFile
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

internal class SourceSelectionDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ABSImportUiState>,
    private val errorBus: ErrorBus,
    private val backupApi: BackupApiContract,
    private val onReadyToAnalyze: (String) -> Unit,
) {
    fun selectSourceType(type: ABSSourceType) {
        state.updateReady { it.copy(sourceType = type, error = null) }
        when (type) {
            ABSSourceType.LOCAL -> {
                // Stay on source selection, user will pick file via UI
            }

            ABSSourceType.REMOTE -> {
                // Navigate to file browser and load root
                state.updateReady { it.copy(step = ABSImportStep.FILE_BROWSER) }
                loadDirectory("/")
            }
        }
    }

    /**
     * Called when user picks a local file via the document picker.
     *
     * @param fileSource Streaming source for the file content (avoids loading into memory)
     * @param filename Original filename for display
     * @param size File size in bytes
     */
    fun setLocalFile(
        fileSource: FileSource,
        filename: String,
        size: Long,
    ) {
        state.updateReady {
            it.copy(
                selectedLocalFile = SelectedLocalFile(fileSource, filename, size),
                error = null,
            )
        }
    }

    /**
     * Clear the selected local file.
     */
    fun clearLocalFile() {
        state.updateReady { it.copy(selectedLocalFile = null) }
    }

    /**
     * Upload the selected local file to the server and proceed to analysis.
     *
     * Uses streaming upload to avoid loading the entire file into memory.
     */
    fun uploadAndAnalyze() {
        val ready = state.ready ?: return
        val file = ready.selectedLocalFile ?: return

        scope.launch {
            state.updateReady { it.copy(step = ABSImportStep.UPLOADING, isUploading = true, error = null) }

            when (val result = backupApi.uploadABSBackup(file.fileSource)) {
                is AppResult.Success -> {
                    val uploadResult = result.data
                    // Proceed to analysis with the returned path
                    state.updateReady {
                        it.copy(
                            isUploading = false,
                            backupPath = uploadResult.path,
                        )
                    }
                    onReadyToAnalyze(uploadResult.path)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to upload ABS backup: ${result.error.message}" }
                    state.updateReady {
                        it.copy(
                            step = ABSImportStep.SOURCE_SELECTION,
                            isUploading = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Load directory contents for the file browser.
     */
    fun loadDirectory(path: String) {
        scope.launch {
            state.updateReady { it.copy(isLoadingDirectories = true, error = null) }

            when (val result = backupApi.browseFilesystem(path)) {
                is AppResult.Success -> {
                    val browseResult = result.data
                    state.updateReady {
                        it.copy(
                            currentPath = browseResult.path,
                            parentPath = browseResult.parent,
                            directories = browseResult.entries,
                            isRoot = browseResult.isRoot,
                            isLoadingDirectories = false,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to browse filesystem: ${result.error.message}" }
                    state.updateReady {
                        it.copy(
                            isLoadingDirectories = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Navigate up to parent directory.
     */
    fun navigateUp() {
        val ready = state.ready ?: return
        val parent = ready.parentPath
        if (parent != null) {
            loadDirectory(parent)
        }
    }

    /**
     * Set the remote file path and proceed to analysis.
     * User enters filename within the current directory.
     */
    fun setRemoteFilePath(filename: String) {
        val ready = state.ready ?: return
        val currentPath = ready.currentPath
        val fullPath =
            if (currentPath.endsWith("/")) {
                "$currentPath$filename"
            } else {
                "$currentPath/$filename"
            }

        state.updateReady { it.copy(selectedRemotePath = fullPath, backupPath = fullPath) }
        onReadyToAnalyze(fullPath)
    }

    /**
     * Set the full remote path directly and proceed to analysis.
     */
    fun setFullRemotePath(path: String) {
        state.updateReady { it.copy(selectedRemotePath = path, backupPath = path) }
        onReadyToAnalyze(path)
    }
}
