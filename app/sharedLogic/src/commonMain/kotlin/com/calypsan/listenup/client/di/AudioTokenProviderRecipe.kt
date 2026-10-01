package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.RotatedTokenPresenter
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import org.koin.core.scope.Scope

/**
 * The one construction of the shared audio-token authority, used by every platform's playback
 * module so the rotation hook can't be forgotten on one of them: each rotation it performs is
 * presented to the server at once ([RotatedTokenPresenter]), which is what lets the server's
 * lost-reply rule tell a reply that never arrived from a stolen token.
 */
internal fun Scope.sharedAudioTokenProvider(): CachedAudioTokenProvider {
    val presenter = get<RotatedTokenPresenter>()
    return CachedAudioTokenProvider(
        authSession = get(),
        authRepository = get(),
        onSessionRotated = presenter::present,
    )
}
