package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.SleepTimerManager
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Verifies Koin module definitions are correctly configured.
 *
 * This test uses Koin's verify() API to statically check that all constructor
 * dependencies have corresponding definitions. This catches issues like:
 * - Missing interface bindings (e.g., concrete class registered but interface not bound)
 * - Missing dependency definitions
 * - Circular dependencies
 *
 * Note: Platform-specific dependencies (DAOs, APIs, etc.) are declared as extraTypes
 * since they're defined in platform modules that can't be loaded in commonTest.
 */
@OptIn(KoinExperimentalAPI::class)
class KoinModuleVerifyTest : FunSpec({
    // Verify voiceModule — the voice intent resolver and its four repository dependencies.
    //
    // Narrow module; the extraTypes list is correspondingly small. Covered as step 1 of
    // the Finding 12 D4 expansion ("every leaf module is verified"). PresentationModule
    // verification is deferred to W3, which rewrites the DI layout (Finding 02 R1) and
    // would immediately invalidate any extraTypes enumerated today.
    test("verifyVoiceModule") {
        voiceModule.verify(
            extraTypes =
                listOf(
                    SearchRepository::class,
                    HomeRepository::class,
                    SeriesRepository::class,
                    BookRepository::class,
                ),
        )
    }

    // Verify [playbackPresentationModule] — single shared playback VM bound as `single`
    // per W7 Phase E2.2.3 consolidation. Closes drift #33 ("playbackPresentationModule
    // not covered by module.verify()").
    //
    // `extraTypes` enumerates cross-module dependencies that other Koin modules satisfy.
    test("verifyPlaybackPresentationModule") {
        playbackPresentationModule.verify(
            extraTypes =
                listOf(
                    PlaybackManager::class,
                    PlaybackController::class,
                    BookRepository::class,
                    SleepTimerManager::class,
                    PlaybackPreferences::class,
                    NetworkMonitor::class,
                ),
        )
    }
})
