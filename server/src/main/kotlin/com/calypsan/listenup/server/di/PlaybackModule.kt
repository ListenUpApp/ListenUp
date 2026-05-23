package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the playback slice. Wires:
 *
 *  - [AudioFileLocator] — resolves `(bookId, fileId)` to an on-disk path for the audio route.
 *  - [AudioUrlSigner] — mints and verifies short-lived signed audio URLs. Derives its signing
 *    key from the JWT secret so operators manage no extra secret.
 *  - [PlaybackPositionRepository] — per-user `(userId, bookId)` resume positions; `createdAtStart = true`
 *    so its `init` block registers `"playback_positions"` with [com.calypsan.listenup.server.sync.SyncRegistry]
 *    at bootstrap.
 *  - [PlaybackService] / [PlaybackServiceImpl] — the RPC+REST implementation. Bound at module level
 *    with an unscoped [PrincipalProvider] placeholder; route handlers call [PlaybackServiceImpl.copyWith]
 *    to scope each request to the authenticated caller.
 *
 * Installed only when a library path is configured (inside the `if (resolvedLibraryPath != null)` guard
 * in [com.calypsan.listenup.server.Application]), consistent with [booksModule] — no library means no
 * audio files to locate or sign URLs for.
 *
 * Exposed as a **function** rather than a top-level `val` so each Koin container receives a fresh
 * [Module], preventing cross-container contamination in tests.
 */
fun playbackModule(): Module =
    module {
        single { AudioFileLocator(get()) }
        single {
            AudioUrlSigner(
                signingKey = AudioUrlSigner.deriveSigningKey(get<JwtConfiguration>().secret),
            )
        }
        single(createdAtStart = true) { PlaybackPositionRepository(get(), get(), get()) }
        single<PlaybackService> {
            PlaybackServiceImpl(
                bookRepository = get<BookRepository>(),
                audioFileLocator = get(),
                audioUrlSigner = get(),
                playbackPositionRepository = get(),
                principal =
                    PrincipalProvider {
                        error(
                            "Unscoped PlaybackService — call copyWith(PrincipalProvider) at the route",
                        )
                    },
            )
        }
    }
