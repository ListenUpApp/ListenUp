package com.calypsan.listenup.client.features.admin.upload

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.components.FlowStep
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_upload_books_step_choose
import listenup.composeapp.generated.resources.admin_upload_books_step_choose_sub
import listenup.composeapp.generated.resources.admin_upload_books_step_import
import listenup.composeapp.generated.resources.admin_upload_books_step_import_sub
import listenup.composeapp.generated.resources.admin_upload_books_step_listen
import listenup.composeapp.generated.resources.admin_upload_books_step_listen_sub
import listenup.composeapp.generated.resources.admin_upload_books_step_upload
import listenup.composeapp.generated.resources.admin_upload_books_step_upload_sub
import org.jetbrains.compose.resources.stringResource

/**
 * The upload's steps as a tablet lists them beside the working area: choosing what to send, the
 * transfer, the server's import, and the books arriving in the library.
 */
@Composable
internal fun uploadFlowSteps(): List<FlowStep> =
    listOf(
        FlowStep(
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(Res.string.admin_upload_books_step_choose),
            subtitle = stringResource(Res.string.admin_upload_books_step_choose_sub),
        ),
        FlowStep(
            icon = Icons.Outlined.CloudUpload,
            title = stringResource(Res.string.admin_upload_books_step_upload),
            subtitle = stringResource(Res.string.admin_upload_books_step_upload_sub),
        ),
        FlowStep(
            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            title = stringResource(Res.string.admin_upload_books_step_import),
            subtitle = stringResource(Res.string.admin_upload_books_step_import_sub),
        ),
        FlowStep(
            icon = Icons.Outlined.Headphones,
            title = stringResource(Res.string.admin_upload_books_step_listen),
            subtitle = stringResource(Res.string.admin_upload_books_step_listen_sub),
        ),
    )

/**
 * Where [this] state sits in [uploadFlowSteps]. A failed session sits on the transfer, where it
 * failed; a finished one has every step done.
 */
internal fun UploadBooksUiState.flowStep(): Int =
    when (this) {
        UploadBooksUiState.Idle -> 0
        is UploadBooksUiState.Uploading, is UploadBooksUiState.Error -> 1
        UploadBooksUiState.Finalizing -> 2
        is UploadBooksUiState.Finished -> UPLOAD_FLOW_STEP_COUNT
    }

private const val UPLOAD_FLOW_STEP_COUNT = 4
