package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.voice.VoiceIntentResolver
import org.koin.dsl.module

/**
 * Koin module for voice intent resolution.
 * Provides VoiceIntentResolver with all required repository dependencies.
 */
val voiceModule =
    module {
        single {
            VoiceIntentResolver(
                searchRepository = get(),
                homeRepository = get(),
                seriesRepository = get(),
                bookRepository = get(),
            )
        }
    }
