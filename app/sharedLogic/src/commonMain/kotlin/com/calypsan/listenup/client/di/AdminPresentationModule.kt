package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel
import com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.admin.InboxBadgeViewModel
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsViewModel
import com.calypsan.listenup.client.presentation.admin.RestoreBackupViewModel
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileViewModel
import com.calypsan.listenup.client.presentation.admin.UserDetailViewModel
import com.calypsan.listenup.client.presentation.admin.UserPermissionsViewModel
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksViewModel
import com.calypsan.listenup.client.presentation.visibility.RestrictedBooksViewModel
import org.koin.dsl.module

/**
 * Admin ViewModels.
 */
internal val adminPresentationModule =
    module {
        // factory (NOT single): koinViewModel() scopes a fresh instance per ViewModelStore. A
        // singleton VM is fatal — when the first owning store is cleared (e.g. navigating through
        // the ABS-import flow), onCleared() cancels the singleton's viewModelScope, so the re-served
        // instance is a zombie: its observeRoster() collector is dead and pendingUsers strands empty
        // until process restart. See libraryPresentationModule's comment and the guard test
        // "no ViewModel is registered as a Koin singleton" (ClientKoinGraphE2ETest).
        factory {
            AdminViewModel(
                getRegistrationPolicyUseCase = get(),
                loadInvitesUseCase = get(),
                deleteUserUseCase = get(),
                revokeInviteUseCase = get(),
                approveUserUseCase = get(),
                denyUserUseCase = get(),
                setRegistrationPolicyUseCase = get(),
                loadPasswordResetRequestsUseCase = get(),
                decidePasswordResetUseCase = get(),
                instanceRepository = get(),
                adminRepository = get(),
            )
        }
        factory { CreateInviteViewModel(createInviteUseCase = get()) }
        factory {
            AdminSettingsViewModel(
                loadServerSettingsUseCase = get(),
                updateServerSettingsUseCase = get(),
                errorBus = get(),
            )
        }
        factory {
            OrganizeSettingsViewModel(
                repository = get(),
                errorBus = get(),
            )
        }
        factory {
            AdminInboxViewModel(
                inboxRepository = get(),
                libraryRepository = get(),
                eventStreamRepository = get(),
                bookDao = get(),
                imageStorage = get(),
                errorBus = get(),
            )
        }
        // The Library entry's and the nav badge's held count — factory, resolved by each shell's own
        // ViewModelStore, like NotificationBellViewModel.
        factory {
            InboxBadgeViewModel(
                userRepository = get(),
                inboxRepository = get(),
            )
        }
        // The lock on every book card: one admin-gated set, published once at each platform's root
        // beside InboxBadgeViewModel. factory (NOT single), scoped to the shell's ViewModelStore.
        factory {
            RestrictedBooksViewModel(
                bookVisibilityRepository = get(),
            )
        }
        factory {
            AdminCollectionsViewModel(
                collectionRepository = get(),
                libraryRepository = get(),
                errorBus = get(),
            )
        }
        factory {
            AdminCategoriesViewModel(
                genreRepository = get(),
                permissionsRepository = get(),
                errorBus = get(),
            )
        }
        // AdminCollectionDetailViewModel - takes collectionId as parameter
        factory { params ->
            AdminCollectionDetailViewModel(
                collectionId = params.get<String>(0),
                collectionRepository = get(),
                adminRepository = get(),
                userRepository = get(),
                userProfileRepository = get(),
                bookDao = get(),
                searchRepository = get(),
                imageStorage = get(),
                errorBus = get(),
            )
        }
        // UserDetailViewModel - takes userId as parameter
        factory { params ->
            UserDetailViewModel(
                userId = params.get<String>(0),
                adminRepository = get(),
                instanceRepository = get(),
                errorBus = get(),
            )
        }
        // UserPermissionsViewModel - takes userId as parameter
        factory { params ->
            UserPermissionsViewModel(
                userId = params.get<String>(0),
                adminRepository = get(),
                instanceRepository = get(),
                errorBus = get(),
            )
        }
        // LibrarySettingsViewModel — operates on THE singleton library (no id param)
        factory {
            LibrarySettingsViewModel(
                adminRepository = get(),
                errorBus = get(),
            )
        }
        // AdminBackupViewModel for backup management
        factory {
            AdminBackupViewModel(
                backupRepository = get(),
                errorBus = get(),
            )
        }
        // UploadBooksViewModel - pick a folder or files and stream them into the library
        factory {
            UploadBooksViewModel(
                uploadRepository = get(),
                errorBus = get(),
            )
        }
        // RestoreFromFileViewModel - pick + upload a .listenup.zip, then hand off to restore
        factory {
            RestoreFromFileViewModel(
                backupRepository = get(),
                errorBus = get(),
            )
        }
        // RestoreBackupViewModel - takes backupId as parameter
        factory { params ->
            RestoreBackupViewModel(
                backupId = params.get<String>(0),
                backupRepository = get(),
                syncRepository = get(),
                errorBus = get(),
            )
        }
        // ABSImportHubViewModel — inline staged-import list (backed by the new ImportService stack).
        // factory (NOT single): koinViewModel()-scoped like every VM; a singleton zombies when its
        // owning store is cleared (see the guard test "no ViewModel is registered as a Koin singleton").
        factory {
            ABSImportHubViewModel(
                importRepository = get(),
                errorBus = get(),
            )
        }
        // ImportFlowViewModel for the rebuilt single-screen ABS import flow
        factory {
            ImportFlowViewModel(
                importRepository = get(),
                errorBus = get(),
                syncRepository = get(),
                adminRepository = get(),
                searchRepository = get(),
            )
        }
    }
