package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.ActiveSessionRepositoryImpl
import com.calypsan.listenup.client.data.repository.ActivityRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl
import com.calypsan.listenup.client.data.repository.LeaderboardRepositoryImpl
import com.calypsan.listenup.client.data.repository.ProfileEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.UserProfileRepositoryImpl
import com.calypsan.listenup.client.data.repository.UserRepositoryImpl
import com.calypsan.listenup.client.data.repository.avatarUploaderOf
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Social aggregate Koin wiring — RPC proxies, repositories, and use cases for the
 * social domain (sessions, profiles, activity feed, leaderboard, readers).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.UserDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.ActivityDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.PublicProfileDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - [com.calypsan.listenup.client.data.sync.PresenceRefreshSignal] — `clientSyncModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 *  - The `AuthServiceAuthed` RPC channel — `clientAuthModule` (resolved via `rpcChannel<AuthServiceAuthed>()`)
 *  - [com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository] — `listeningModule`
 */
internal val socialModule: Module =
    module {
        // SocialService RPC channel — kotlinx.rpc dispatch for SocialService (Room reads; RPC
        // reads for presence/readership). Authed (self-healing) by default; joins the
        // RpcCacheInvalidator sweep.
        rpcChannel<SocialService>()

        // ProfileService RPC channel — kotlinx.rpc dispatch for ProfileService (profile
        // read/update mutations). Authed (self-healing) by default; joins the RpcCacheInvalidator sweep.
        rpcChannel<ProfileService>()

        // ProfileEditRepository for profile editing operations: name/tagline offline-first via
        // OfflineEditor, password + avatar stay online (RPC-dispatched mutations).
        single<ProfileEditRepository> {
            ProfileEditRepositoryImpl(
                userDao = get(),
                publicProfileDao = get(),
                channel = rpcChannel(),
                avatarUploader = avatarUploaderOf(get()),
                imageStorage = get(),
                offlineEditor = get(),
            )
        }

        // UserRepository for current user profile data (SOLID: interface in domain, impl in data).
        // Refreshes the current user over the bearer-gated AuthServiceAuthed channel owned by
        // clientAuthModule.
        single<UserRepository> {
            UserRepositoryImpl(userDao = get(), authedChannel = rpcChannel())
        }

        // UserProfileRepository resolves EVERY user's avatar profile (self + others) from the
        // synced public_profiles roster, so avatars render everywhere instead of only for self.
        single<UserProfileRepository> {
            UserProfileRepositoryImpl(publicProfileDao = get())
        }

        // LeaderboardRepository — Room-observed, offline-first; reads the synced
        // public_profiles roster so all users appear in each other's leaderboard.
        single<LeaderboardRepository> {
            LeaderboardRepositoryImpl(publicProfileDao = get())
        }

        // ActivityRepository for activity feed (SOLID: interface in domain, impl in data)
        single<ActivityRepository> {
            ActivityRepositoryImpl(dao = get())
        }

        // ActiveSessionRepository for live sessions — SocialService RPC + local-Room book enrich,
        // re-fetched on every PresenceRefreshSignal ping (server nudge or firehose reconnect).
        single<ActiveSessionRepository> {
            ActiveSessionRepositoryImpl(
                channel = rpcChannel(),
                bookDao = get(),
                imageStorage = get(),
                presence = get(),
                cachedSessionDao = get(),
            )
        }

        // BookReadersRepository for Book Detail Readers section — maps the ACL-filtered SocialService
        // bookReadership RPC (which includes the caller) to domain readers, flagging the current user,
        // re-fetched on every PresenceRefreshSignal ping.
        single<BookReadersRepository> {
            BookReadersRepositoryImpl(
                channel = rpcChannel(),
                presence = get(),
                userRepository = get(),
                readershipDao = get(),
            )
        }
    }
