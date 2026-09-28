package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.HardcoverRepositoryImpl
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Hardcover connection Koin wiring — the RPC channel and repository for the user's Hardcover
 * account link.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 */
internal val hardcoverClientModule: Module =
    module {
        // HardcoverService RPC channel — connect, disconnect and the live connection watch.
        // Authed (self-healing) by default.
        rpcChannel<HardcoverService>()

        single<HardcoverRepository> { HardcoverRepositoryImpl(rpcChannel()) }
    }
