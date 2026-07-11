package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.data.remote.BackupRpcFactory
import com.calypsan.listenup.client.data.remote.ImportRpcFactory
import com.calypsan.listenup.client.data.remote.KtorBackupRpcFactory
import com.calypsan.listenup.client.data.remote.KtorImportRpcFactory
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.AdminRepositoryImpl
import com.calypsan.listenup.client.data.repository.BackupRepositoryImpl
import com.calypsan.listenup.client.data.repository.EventStreamRepositoryImpl
import com.calypsan.listenup.client.data.repository.ImportRepositoryImpl
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.ImportRepository
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
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Admin aggregate Koin wiring — RPC proxies, repositories, and use cases for the
 * admin domain (user management, server settings, backup/restore, ABS import).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.core.error.ErrorBus] — `appCoreModule`
 *  - [com.calypsan.listenup.client.data.remote.InviteRpcFactory] — `clientAuthModule`
 */
internal val adminModule: Module =
    module {
        // LibraryAdminService RPC channel — kotlinx.rpc dispatch for library/folder administration.
        // Authed (self-healing) by default; joins the RpcCacheInvalidator sweep.
        rpcChannel<LibraryAdminService>()

        // AdminUserService RPC channel — user roster, approval queue, role/permission edits.
        rpcChannel<AdminUserService>()

        // AdminSettingsService RPC channel — server-identity settings (server name + remote URL).
        rpcChannel<AdminSettingsService>()

        // BackupRpcFactory — kotlinx.rpc proxy for BackupService (admin backup/restore over RPC).
        single<BackupRpcFactory> {
            KtorBackupRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // ImportRpcFactory — kotlinx.rpc proxy for ImportService (admin Audiobookshelf import over RPC).
        single<ImportRpcFactory> {
            KtorImportRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // BackupRepository — admin backup/restore via BackupService RPC proxy + REST upload.
        single<BackupRepository> {
            BackupRepositoryImpl(rpcFactory = get(), clientFactory = get())
        }

        // ImportRepository — admin Audiobookshelf import via ImportService RPC proxy + REST upload.
        single<ImportRepository> {
            ImportRepositoryImpl(rpcFactory = get(), clientFactory = get())
        }

        // AdminRepository for admin operations (SOLID: interface in domain, impl in data)
        single<AdminRepository> {
            AdminRepositoryImpl(
                adminUserChannel = rpcChannel(),
                adminSettingsChannel = rpcChannel(),
                inviteRpc = get(),
                libraryAdminChannel = rpcChannel(),
                serverConfig = get(),
                adminUserRosterDao = get(),
                rpcCacheInvalidator = get(),
            )
        }

        // EventStreamRepository for real-time events (SOLID: interface in domain, impl in data)
        single<EventStreamRepository> {
            EventStreamRepositoryImpl()
        }

        // Admin user management use cases
        factory {
            LoadUsersUseCase(
                adminRepository = get(),
            )
        }
        factory {
            LoadPendingUsersUseCase(
                adminRepository = get(),
            )
        }
        factory {
            LoadInvitesUseCase(
                adminRepository = get(),
            )
        }
        factory {
            DeleteUserUseCase(
                adminRepository = get(),
            )
        }
        factory {
            RevokeInviteUseCase(
                adminRepository = get(),
            )
        }
        factory {
            ApproveUserUseCase(
                adminRepository = get(),
            )
        }
        factory {
            DenyUserUseCase(
                adminRepository = get(),
            )
        }
        factory {
            GetRegistrationPolicyUseCase(
                adminRepository = get(),
            )
        }
        factory {
            SetRegistrationPolicyUseCase(
                adminRepository = get(),
            )
        }
        factory {
            CreateInviteUseCase(
                adminRepository = get(),
            )
        }
        factory {
            LoadServerSettingsUseCase(
                adminRepository = get(),
            )
        }
        factory {
            UpdateServerSettingsUseCase(
                adminRepository = get(),
            )
        }
    }
