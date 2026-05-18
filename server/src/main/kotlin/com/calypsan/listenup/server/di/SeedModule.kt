package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.seed.SeedRunner
import com.calypsan.listenup.server.seed.UserDomainSeeder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the demo seed profile. Installed by `Application.module()` only
 * when `seed.profile = demo`. The `SeedRunner`'s seeder list is hand-assembled —
 * every future domain phase adds its `DomainSeeder` to this list.
 */
fun seedModule(): Module =
    module {
        single { UserDomainSeeder(db = get(), authService = get()) }
        single { SeedRunner(seeders = listOf(get<UserDomainSeeder>())) }
    }
