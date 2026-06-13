package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.features.admin.AdminScreen
import com.calypsan.listenup.client.features.admin.CreateInviteScreen
import com.calypsan.listenup.client.features.admin.backup.AdminBackupScreen
import com.calypsan.listenup.client.features.admin.backup.ABSImportHubDetailScreen
import com.calypsan.listenup.client.features.admin.backup.ABSImportScreen
import com.calypsan.listenup.client.features.admin.import.ImportFlowScreen
import com.calypsan.listenup.client.features.admin.backup.CreateBackupScreen
import com.calypsan.listenup.client.features.admin.backup.RestoreBackupScreen
import com.calypsan.listenup.client.navigation.ABSImport
import com.calypsan.listenup.client.navigation.ABSImportDetail
import com.calypsan.listenup.client.navigation.ImportFlow
import com.calypsan.listenup.client.navigation.Admin
import com.calypsan.listenup.client.navigation.AdminBackups
import com.calypsan.listenup.client.navigation.AdminCategories
import com.calypsan.listenup.client.navigation.AdminCollectionDetail
import com.calypsan.listenup.client.navigation.AdminCollections
import com.calypsan.listenup.client.navigation.AdminLibrarySettings
import com.calypsan.listenup.client.navigation.AdminUserDetail
import com.calypsan.listenup.client.navigation.BookEdit
import com.calypsan.listenup.client.navigation.BrowseGenre
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.CreateBackup
import com.calypsan.listenup.client.navigation.CreateInvite
import com.calypsan.listenup.client.navigation.RestoreBackup
import com.calypsan.listenup.client.navigation.UnmappedGenres
import com.calypsan.listenup.client.navigation.AdminInbox
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.unmappedgenres.UnmappedGenresViewModel
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
            onUnmappedGenresClick = {
                backStack.add(UnmappedGenres)
            },
            onBackupClick = {
                backStack.add(AdminBackups)
            },
            onUserClick = { userId ->
                backStack.add(AdminUserDetail(userId))
            },
            serverName = readySettings?.serverName ?: "",
            onServerNameChange = { settingsViewModel.setServerName(it) },
            remoteUrl = readySettings?.remoteUrl ?: "",
            onRemoteUrlChange = { settingsViewModel.setRemoteUrl(it) },
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
    entry<BrowseGenre> {
        val viewModel: BrowseGenreViewModel = koinViewModel()
        com.calypsan.listenup.client.features.browsegenre.BrowseGenreScreen(
            viewModel = viewModel,
            onBackClick = { backStack.removeAt(backStack.lastIndex) },
            onBookClick = { bookId -> backStack.add(BookDetail(bookId.value)) },
        )
    }
    entry<UnmappedGenres> {
        val viewModel: UnmappedGenresViewModel = koinViewModel()
        com.calypsan.listenup.client.features.unmappedgenres.UnmappedGenresScreen(
            viewModel = viewModel,
            onBackClick = { backStack.removeAt(backStack.lastIndex) },
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
    entry<AdminLibrarySettings> { args ->
        val viewModel:
            com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel =
            koinViewModel {
                org.koin.core.parameter
                    .parametersOf(args.libraryId)
            }
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
            onABSImportHubClick = { importId ->
                backStack.add(ABSImportDetail(importId))
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
    // ABSImportList removed - imports are now shown inline in AdminBackupScreen
    entry<ABSImportDetail> { args ->
        ABSImportHubDetailScreen(
            importId = args.importId,
            onBackClick = { backStack.removeAt(backStack.lastIndex) },
        )
    }
    entry<ABSImport> {
        ABSImportScreen(
            onBackClick = { backStack.removeAt(backStack.lastIndex) },
            onComplete = {
                // Navigate back to backup list after import
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<ImportFlow> {
        ImportFlowScreen(
            onNavigateBack = { backStack.removeAt(backStack.lastIndex) },
        )
    }
}
