package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.repository.LoadedBookRepositoryImpl
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.playback.PlayerSession
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.core.appCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the player's process-lifetime scope. */
internal const val PLAYER_SCOPE = "playerScope"

/** Qualifier for "this platform keeps downloads" (the browser does not). Bound by each platform playback module. */
internal const val OFFLINE_COPIES = "offlineCopiesSupported"

/**
 * The shared player (spec §4): the loaded-book repository, the session that owns playback, and the one sleep
 * timer. The [com.calypsan.listenup.client.playback.PlayerEngine] and the [OFFLINE_COPIES] flag come from the
 * platform playback module. The session is created at start so a book started with no UI (Android Auto,
 * Media3 resumption) still becomes this device's loaded book.
 */
internal val playerModule =
    module {
        // The session's load generation and job are only touched on the main thread, like a viewModelScope.
        // This module is a composition root: it is where the player scope's dispatcher is chosen.
        single(named(PLAYER_SCOPE)) {
            @Suppress("InjectDispatcher")
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + appCoroutineExceptionHandler)
        }
        single { SleepTimerManager(scope = get(named(PLAYER_SCOPE))) }
        single<LoadedBookRepository> {
            LoadedBookRepositoryImpl(store = get(), bookRepository = get(), positionRepository = get(), engine = get())
        }
        single(createdAtStart = true) {
            PlayerSession(
                engine = get(),
                loadedBooks = get(),
                bookRepository = get(),
                downloadRepository = get(),
                positionRepository = get(),
                availability = get(),
                inboxRepository = get(),
                networkMonitor = get(),
                serverReachability = get(),
                authSession = get(),
                sleepTimer = get(),
                playbackPreferences = get(),
                offlineCopiesSupported = get(named(OFFLINE_COPIES)),
                scope = get(named(PLAYER_SCOPE)),
            )
        }
        // PlayerSheetState and the PlayerViewModel factory join in Task 20.
    }
