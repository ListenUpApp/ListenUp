package com.calypsan.listenup.client.di

import android.content.Context
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playbackModule
import io.kotest.core.spec.style.FunSpec
import org.koin.test.verify.verify

/**
 * Static dependency check for [playbackModule] via Koin's `verify()`.
 *
 * `verify()` walks every `single { }` and asserts each constructor dependency is
 * either declared in the module or listed in [extraTypes] (provided by other
 * modules at runtime). It catches missing-binding regressions WITHOUT
 * instantiating anything — no Android Context, no MediaController.
 *
 * Regression guard: this fails if the `PlaybackStateWriter` write-seam binding is
 * dropped — exactly the gap that crashed NowPlaying when `MediaControllerHolder`
 * (which depends on `PlaybackStateWriter`, not the full `PlaybackManager`) could
 * not resolve its dependency.
 */
class PlaybackModuleVerifyTest :
    FunSpec({
        test("playbackModule resolves every binding's dependencies") {
            playbackModule.verify(
                extraTypes =
                    listOf(
                        // Android + cross-module externals provided by androidModule /
                        // sharedModules at runtime, not by playbackModule itself.
                        Context::class,
                        ServerConfig::class,
                        AuthSession::class,
                        AuthRepository::class,
                        BookRepository::class,
                        HomeRepository::class,
                        DownloadRepository::class,
                        ListeningEventRepository::class,
                        PlaybackPositionRepository::class,
                        PlaybackPreferences::class,
                        DeviceContext::class,
                        ImageStorage::class,
                        PlaybackManager::class,
                        PlaybackPrepareRepository::class,
                        ContributorRepository::class,
                        SeriesRepository::class,
                    ),
            )
        }
    })
