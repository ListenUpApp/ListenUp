package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.server.api.ProfileServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.routes.AVATAR_MAX_BYTES
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/** Profile RPC service + avatar ImageStore wiring. [avatarsDir] is `$LISTENUP_HOME/avatars`. */
fun profileModule(avatarsDir: Path): Module =
    module {
        single { ImageStore(avatarsDir, maxBytes = AVATAR_MAX_BYTES) }
        single<ProfileService> {
            ProfileServiceImpl(
                sql = get<ListenUpDatabase>(),
                passwordHasher = get(),
                publicProfileMaintainer = get(),
                imageStore = get(),
                clock = get(),
            )
        }
    }
