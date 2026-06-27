package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.CreateInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.GetRegistrationPolicyUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPendingUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetRegistrationPolicyUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UpdateServerSettingsUseCase
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [adminPresentationModule]. Per the architecture rubric every leaf Koin
 * module is covered by a `module.verify()` test in commonTest. The whitelist enumerates
 * dependencies that [adminPresentationModule] pulls in but other modules own:
 *
 *  - [GetRegistrationPolicyUseCase] — owned by `adminModule`.
 *  - [LoadUsersUseCase] — owned by `adminModule`.
 *  - [LoadPendingUsersUseCase] — owned by `adminModule`.
 *  - [LoadInvitesUseCase] — owned by `adminModule`.
 *  - [DeleteUserUseCase] — owned by `adminModule`.
 *  - [RevokeInviteUseCase] — owned by `adminModule`.
 *  - [ApproveUserUseCase] — owned by `adminModule`.
 *  - [DenyUserUseCase] — owned by `adminModule`.
 *  - [SetRegistrationPolicyUseCase] — owned by `adminModule`.
 *  - [EventStreamRepository] — owned by `adminModule`.
 *  - [CreateInviteUseCase] — owned by `adminModule`.
 *  - [LoadServerSettingsUseCase] — owned by `adminModule`.
 *  - [UpdateServerSettingsUseCase] — owned by `adminModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 *  - [InboxRepository] — owned by `adminModule`.
 *  - [LibraryRepository] — owned by `libraryModule`.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [ImageStorage] — owned by `mediaModule`.
 *  - [CollectionRepository] — owned by `collectionModule`.
 *  - [GenreRepository] — owned by `genreTagModule` (pulled in by `BrowseGenreViewModel`).
 *  - [TagRepository] — owned by `genreTagModule` (pulled in by `BrowseFacetViewModel`).
 *  - [MoodRepository] — owned by `genreTagModule` (pulled in by `BrowseFacetViewModel`).
 *  - [BookRepository] — owned by `bookModule` (pulled in by `BrowseFacetViewModel`).
 *  - [AdminRepository] — owned by `adminModule`.
 *  - [UserRepository] — owned by `socialModule`.
 *  - [SearchRepository] — owned by `searchModule`.
 *  - [BackupRepository] — owned by `adminModule`.
 *  - [SyncRepository] — owned by `clientSyncRenovationModule`.
 *  - [ImportRepository] — owned by `adminModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class AdminPresentationModuleVerifyTest :
    FunSpec({

        test("adminPresentationModule wires up against its declared external dependencies") {
            adminPresentationModule.verify(
                extraTypes =
                    listOf(
                        GetRegistrationPolicyUseCase::class,
                        LoadUsersUseCase::class,
                        LoadPendingUsersUseCase::class,
                        LoadInvitesUseCase::class,
                        DeleteUserUseCase::class,
                        RevokeInviteUseCase::class,
                        ApproveUserUseCase::class,
                        DenyUserUseCase::class,
                        SetRegistrationPolicyUseCase::class,
                        EventStreamRepository::class,
                        CreateInviteUseCase::class,
                        LoadServerSettingsUseCase::class,
                        UpdateServerSettingsUseCase::class,
                        ErrorBus::class,
                        InboxRepository::class,
                        LibraryRepository::class,
                        BookDao::class,
                        ImageStorage::class,
                        CollectionRepository::class,
                        GenreRepository::class,
                        TagRepository::class,
                        MoodRepository::class,
                        BookRepository::class,
                        AdminRepository::class,
                        UserRepository::class,
                        SearchRepository::class,
                        BackupRepository::class,
                        SyncRepository::class,
                        ImportRepository::class,
                    ),
            )
        }
    })
