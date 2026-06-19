package com.calypsan.listenup.server.cover

import com.calypsan.listenup.server.media.ImageStore

/**
 * The cover-scoped [ImageStore] (rooted at `$LISTENUP_HOME/covers`). A distinct type so it doesn't
 * collide with the avatar [ImageStore] Koin binding in
 * [com.calypsan.listenup.server.di.profileModule].
 */
data class CoverImageStore(
    val store: ImageStore,
)
