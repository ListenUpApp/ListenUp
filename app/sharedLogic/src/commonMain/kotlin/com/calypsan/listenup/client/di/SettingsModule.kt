package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.repository.SettingsRepositoryImpl
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Settings domain wiring — [SettingsRepositoryImpl] and its five segregated interface
 * bindings ([ServerConfig], [LibrarySync], [LibraryPreferences], [PlaybackPreferences],
 * [LocalPreferences]). All five are segregated views of the same singleton instance.
 */
internal val settingsModule: Module =
    module {
        // AuthSession (tokens + AuthState flow) is provided by clientAuthModule.
        // SettingsRepositoryImpl depends on AuthSession, but AuthSessionStore (the
        // AuthSession impl) depends on ServerConfig, which resolves back to
        // SettingsRepositoryImpl — a construction-time cycle. The cycle is broken by
        // injecting AuthSession as Lazy<AuthSession>: the lambda body runs only on
        // first suspend-method use, by which time SettingsRepositoryImpl is fully
        // constructed and registered in the Koin graph.

        // Settings repository — everything *non-auth*: server-URL plumbing, library identity,
        // library + playback preferences, device-local UI preferences. Emits preference change
        // events for PreferencesSyncObserver (in clientSyncModule) to consume without circular deps.
        single {
            val scope = this
            SettingsRepositoryImpl(
                secureStorage = get(),
                authSession = lazy { scope.get<AuthSession>() },
            )
        }

        // Bind the remaining segregated interfaces to the same SettingsRepositoryImpl instance.
        single<ServerConfig> { get<SettingsRepositoryImpl>() }
        single<LibrarySync> { get<SettingsRepositoryImpl>() }
        single<LibraryPreferences> { get<SettingsRepositoryImpl>() }
        single<PlaybackPreferences> { get<SettingsRepositoryImpl>() }
        single<LocalPreferences> { get<SettingsRepositoryImpl>() }
    }
