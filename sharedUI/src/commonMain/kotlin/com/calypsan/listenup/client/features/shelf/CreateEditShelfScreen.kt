@file:Suppress("LongMethod")

package com.calypsan.listenup.client.features.shelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.calypsan.listenup.client.design.components.ListenUpTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfNavAction
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.shelf_delete_shelf
import listenup.composeapp.generated.resources.shelf_description_optional
import listenup.composeapp.generated.resources.common_shelf_name_hint
import listenup.composeapp.generated.resources.shelf_this_will_permanently_delete_this
import listenup.composeapp.generated.resources.shelf_whats_this_shelf_for

/**
 * Screen for creating or editing a shelf.
 *
 * @param shelfId If provided, edit this shelf. If null, create a new shelf.
 * @param onBack Callback for back navigation (triggered on save/delete success too)
 * @param viewModel The ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEditShelfScreen(
    shelfId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateEditShelfViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    val isEditing = shelfId != null

    // Reset text seed when shelfId changes; key() scopes the saveables so a new
    // shelfId discards stale text and re-seeds from the next Loaded emission.
    var name by rememberSaveable(shelfId) { mutableStateOf("") }
    var description by rememberSaveable(shelfId) { mutableStateOf("") }
    var seeded by rememberSaveable(shelfId) { mutableStateOf(false) }

    LaunchedEffect(shelfId) {
        if (shelfId != null) viewModel.initEdit(shelfId) else viewModel.initCreate()
    }

    LaunchedEffect(state) {
        val current = state
        if (current is CreateEditShelfUiState.Loaded && !seeded) {
            name = current.name
            description = current.description
            seeded = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                CreateEditShelfNavAction.NavigateBack -> onBack()
            }
        }
    }

    LaunchedEffect(state) {
        val current = state
        if (current is CreateEditShelfUiState.Error) {
            snackbarHostState.showSnackbar(current.message)
            viewModel.dismissError()
        }
    }

    val isLoading = state is CreateEditShelfUiState.LoadingExisting
    val isSaving = state is CreateEditShelfUiState.Saving

    Scaffold(
        topBar = {
            CreateEditShelfTopBar(
                isEditing = isEditing,
                onBack = onBack,
                onDeleteClick = { showDeleteDialog = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        if (isLoading) {
            ListenUpLoadingIndicator(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            )
        } else {
            CreateEditShelfFormBody(
                name = name,
                description = description,
                isEditing = isEditing,
                isSaving = isSaving,
                canSave = name.isNotBlank() && !isSaving,
                onNameChange = { name = it },
                onDescriptionChange = { description = it },
                onSave = { viewModel.save(name, description) },
                paddingValues = paddingValues,
            )
        }

        if (showDeleteDialog) {
            DeleteShelfDialog(
                onConfirm = {
                    showDeleteDialog = false
                    viewModel.delete()
                },
                onDismiss = { showDeleteDialog = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEditShelfTopBar(
    isEditing: Boolean,
    onBack: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    TopAppBar(
        title = { Text(text = if (isEditing) "Edit Shelf" else "Create Shelf") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
        },
        actions = {
            if (isEditing) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.shelf_delete_shelf),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}

@Composable
private fun CreateEditShelfFormBody(
    name: String,
    description: String,
    isEditing: Boolean,
    isSaving: Boolean,
    canSave: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
    ) {
        ListenUpTextField(
            value = name,
            onValueChange = onNameChange,
            label = "Name",
            placeholder = stringResource(Res.string.common_shelf_name_hint),
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(Res.string.shelf_description_optional)) },
            placeholder = { Text(stringResource(Res.string.shelf_whats_this_shelf_for)) },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text =
                    when {
                        isSaving -> "Saving..."
                        isEditing -> "Save Changes"
                        else -> "Create Shelf"
                    },
            )
        }
    }
}

@Composable
private fun DeleteShelfDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.shelf_delete_shelf)) },
        text = { Text(stringResource(Res.string.shelf_this_will_permanently_delete_this)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(stringResource(Res.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}
