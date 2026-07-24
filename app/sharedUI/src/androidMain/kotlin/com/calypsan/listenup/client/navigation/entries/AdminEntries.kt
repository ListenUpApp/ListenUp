package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.features.admin.AdminScreen
import com.calypsan.listenup.client.features.admin.CreateInviteScreen
import com.calypsan.listenup.client.features.admin.backup.AdminBackupScreen
import com.calypsan.listenup.client.features.admin.import.ImportFlowScreen
import com.calypsan.listenup.client.features.admin.backup.CreateBackupScreen
import com.calypsan.listenup.client.features.admin.backup.RestoreBackupScreen
import com.calypsan.listenup.client.features.admin.backup.RestoreFromFileScreen
import com.calypsan.listenup.client.navigation.ImportFlow
import com.calypsan.listenup.client.navigation.MetadataSearch
import com.calypsan.listenup.client.navigation.Admin
import com.calypsan.listenup.client.navigation.AdminBackups
import com.calypsan.listenup.client.navigation.AdminCategories
import com.calypsan.listenup.client.navigation.AdminCollectionDetail
import com.calypsan.listenup.client.navigation.AdminCollections
import com.calypsan.listenup.client.navigation.AdminLibrarySettings
import com.calypsan.listenup.client.navigation.AdminUserDetail
import com.calypsan.listenup.client.navigation.BookEdit
import com.calypsan.listenup.client.navigation.CreateBackup
import com.calypsan.listenup.client.navigation.CreateInvite
import com.calypsan.listenup.client.navigation.RestoreBackup
import com.calypsan.listenup.client.navigation.RestoreFromFile
import com.calypsan.listenup.client.navigation.AdminInbox
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.error.localized
import org.koin.compose.viewmodel.koinViewModel

/** Admin navigation entries (main admin screens). */
internal fun EntryProviderScope<NavKey>.adminEntries(backStack: NavBackStack<NavKey>) {
    entry<Admin> {
        val viewModel: AdminViewModel = koinViewModel()
        val settingsViewModel: AdminSettingsViewModel = koinViewModel()
        val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
        val readySettings = settingsState as? AdminSettingsUiState.Ready

        AdminScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onInviteClick = {
                backStack.add(CreateInvite)
            },
            onCollectionsClick = {
                backStack.add(AdminCollections)
            },
            onCategoriesClick = {
                backStack.add(AdminCategories)
            },
            onBackupClick = {
                backStack.add(AdminBackups)
            },
            onImportClick = {
                backStack.add(ImportFlow)
            },
            onInboxClick = {
                backStack.add(AdminInbox)
            },
            onLibrarySettingsClick = {
                backStack.add(AdminLibrarySettings)
            },
            onUserClick = { userId ->
                backStack.add(AdminUserDetail(userId))
            },
            serverName = readySettings?.serverName ?: "",
            onServerNameChange = { settingsViewModel.setServerName(it) },
            remoteUrl = readySettings?.remoteUrl ?: "",
            onRemoteUrlChange = { settingsViewModel.setRemoteUrl(it) },
            inboxEnabled = readySettings?.inboxEnabled ?: false,
            onInboxEnabledChange = { settingsViewModel.setInboxEnabled(it) },
            isDirty = readySettings?.isDirty == true,
            onSave = { settingsViewModel.saveAll() },
            settingsError =
                (
                    readySettings?.error
                        ?: (settingsState as? AdminSettingsUiState.Error)?.error
                )?.localized(),
            onClearSettingsError = { settingsViewModel.clearError() },
        )
    }
    entry<AdminInbox> {
        val viewModel: AdminInboxViewModel = koinViewModel()
        com.calypsan.listenup.client.features.admin.inbox.AdminInboxScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            // Tapping a row opens book-edit to fix tags/collections before release.
            onBookClick = { bookId ->
                backStack.add(BookEdit(bookId))
            },
            // Per-row "Match on Audible" — opens the metadata match wizard for that book (iOS parity).
            onMatchClick = { bookId ->
                backStack.add(MetadataSearch(bookId))
            },
        )
    }
    entry<AdminCategories> {
        val viewModel: AdminCategoriesViewModel = koinViewModel()
        com.calypsan.listenup.client.features.admin.categories.AdminCategoriesScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<CreateInvite> {
        val viewModel: CreateInviteViewModel = koinViewModel()
        CreateInviteScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onSuccess = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    adminDetailEntries(backStack)
    adminMaintenanceEntries(backStack)
}

/** Admin collection + user + library detail entries. */
internal fun EntryProviderScope<NavKey>.adminDetailEntries(backStack: NavBackStack<NavKey>) {
    entry<AdminCollections> {
        val viewModel: com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel =
            koinViewModel()
        com.calypsan.listenup.client.features.admin.collections.AdminCollectionsScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onCollectionClick = { collectionId ->
                backStack.add(AdminCollectionDetail(collectionId))
            },
        )
    }
    entry<AdminCollectionDetail> { args ->
        val viewModel:
            com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel =
            koinViewModel {
                org.koin.core.parameter
                    .parametersOf(args.collectionId)
            }
        com.calypsan.listenup.client.features.admin.collections.AdminCollectionDetailScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<AdminUserDetail> { args ->
        val viewModel:
            com.calypsan.listenup.client.presentation.admin.UserDetailViewModel =
            koinViewModel {
                org.koin.core.parameter
                    .parametersOf(args.userId)
            }
        com.calypsan.listenup.client.features.admin.UserDetailScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<AdminLibrarySettings> {
        val viewModel: com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel =
            koinViewModel()
        com.calypsan.listenup.client.features.admin.LibrarySettingsScreen(
            viewModel = viewModel,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}

/** Admin maintenance navigation entries (backups, restores, ABS import). */
internal fun EntryProviderScope<NavKey>.adminMaintenanceEntries(backStack: NavBackStack<NavKey>) {
    entry<AdminBackups> {
        AdminBackupScreen(
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onCreateClick = {
                backStack.add(CreateBackup)
            },
            onRestoreClick = { backupId ->
                backStack.add(RestoreBackup(backupId))
            },
            onRestoreFromFileClick = {
                backStack.add(RestoreFromFile)
            },
            onABSImportHubClick = {
                // The persistent per-import detail editor is gone; the linear ImportFlow now owns
                // analyze→review→apply. Tapping a staged import resumes into that flow.
                backStack.add(ImportFlow)
            },
            onNewImportClick = {
                backStack.add(ImportFlow)
            },
        )
    }
    entry<CreateBackup> {
        CreateBackupScreen(
            onBackClick = { backStack.removeAt(backStack.lastIndex) },
            onSuccess = {
                // Navigate back to backup list after successful creation
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<RestoreBackup> { args ->
        RestoreBackupScreen(
            backupId = args.backupId,
            onBackClick = { backStack.removeAt(backStack.lastIndex) },
            onComplete = {
                // Navigate back to backup list after restore
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    // The legacy ABSImport wizard and per-import detail hub were removed — the linear ImportFlow
    // is the single ABS import surface; the import list lives inline in AdminBackupScreen.
    entry<ImportFlow> {
        ImportFlowScreen(
            onNavigateBack = { backStack.removeAt(backStack.lastIndex) },
        )
    }
    entry<RestoreFromFile> {
        RestoreFromFileScreen(
            onBackClick = { backStack.removeAt(backStack.lastIndex) },
            onUploaded = { backupId ->
                // Drop the upload step, continue into the existing restore-confirmation flow.
                backStack.removeAt(backStack.lastIndex)
                backStack.add(RestoreBackup(backupId.value))
            },
        )
    }
}
