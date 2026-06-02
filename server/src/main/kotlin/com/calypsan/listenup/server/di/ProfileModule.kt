package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.server.api.ProfileServiceImpl
import org.koin.core.module.Module
import org.koin.dsl.module

/** Profile RPC service wiring. (ImageStore + avatar routes are wired separately.) */
fun profileModule(): Module =
    module {
        single<ProfileService> { ProfileServiceImpl(db = get(), passwordHasher = get()) }
    }
