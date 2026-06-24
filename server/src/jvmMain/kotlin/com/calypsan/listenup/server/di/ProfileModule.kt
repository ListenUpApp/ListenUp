package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.server.api.ProfileServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.routes.AVATAR_MAX_BYTES
import java.nio.file.Path
import kotlinx.io.files.Path as IoPath
import org.koin.core.module.Module
import org.koin.dsl.module

/** Profile RPC service + avatar ImageStore wiring. [avatarsDir] is `$LISTENUP_HOME/avatars`. */
fun profileModule(avatarsDir: Path): Module =
    module {
        single<ProfileService> {
            ProfileServiceImpl(
                sql = get<ListenUpDatabase>(),
                passwordHasher = get(),
                publicProfileMaintainer = get(),
                clock = get(),
            )
        }
        single { ImageStore(IoPath(avatarsDir.toString()), maxBytes = AVATAR_MAX_BYTES) }
    }
