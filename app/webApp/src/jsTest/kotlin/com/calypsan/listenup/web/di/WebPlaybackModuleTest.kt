package com.calypsan.listenup.web.di

import com.calypsan.listenup.client.di.jsSharedModules
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.web.createSqliteWorker
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.w3c.dom.Worker

/**
 * `browserPlaybackModule` binds `playbackAvailable = false` and says in its own KDoc that this is
 * "a statement about today". Today has arrived: the web app supplies a player, so its module
 * overrides that binding — the same platform-modules-load-last pattern `platformStorageModule`
 * already uses to override the filesystem DocumentStorage.
 */
class WebPlaybackModuleTest :
    FunSpec({
        test("the web app's module reports playback as available") {
            val koin = startKoin { modules(jsSharedModules() + webPlaybackModule) }.koin
            try {
                koin.get<Boolean>(named("playbackAvailable")) shouldBe true
            } finally {
                stopKoin()
            }
        }

        test("the graph constructs a real player and controller") {
            // The full graph, same shape as Main.kt: PlaybackController's chain reaches down
            // through PlaybackManager to the Room database, which needs the worker binding
            // :app:sharedLogic ships no worker script for.
            val koin =
                startKoin {
                    modules(
                        jsSharedModules() +
                            webPlaybackModule +
                            module { single<Worker> { createSqliteWorker() } },
                    )
                }.koin
            try {
                koin.get<AudioPlayer>()
                koin.get<PlaybackController>()
            } finally {
                stopKoin()
            }
        }

        test("AudioPlayer and HtmlAudioPlayer resolve to the identical singleton") {
            // Pins the shape WebPlaybackModule's KDoc calls load-bearing: `single<AudioPlayer> {
            // HtmlAudioPlayer() }` would compile and pass every other test here while quietly
            // constructing a SECOND `<audio>` element — one the transport bar drives, a different,
            // silent one PlaybackManager actually plays through. No other assertion in this file
            // would catch that regression.
            val koin = startKoin { modules(jsSharedModules() + webPlaybackModule) }.koin
            try {
                koin.get<AudioPlayer>() shouldBeSameInstanceAs koin.get<HtmlAudioPlayer>()
            } finally {
                stopKoin()
            }
        }
    })
