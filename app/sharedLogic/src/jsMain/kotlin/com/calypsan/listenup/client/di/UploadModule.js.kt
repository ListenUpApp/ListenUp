package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.VersionHeaders
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.ArchiveUploadApi
import com.calypsan.listenup.client.data.remote.ArchiveUploadApiContract
import com.calypsan.listenup.client.data.remote.BrowserArchiveUploadApi
import com.calypsan.listenup.client.data.remote.BrowserUploadApi
import com.calypsan.listenup.client.data.remote.UploadApi
import com.calypsan.listenup.client.data.remote.UploadApiContract
import com.calypsan.listenup.client.data.remote.XhrUploadTransport
import com.calypsan.listenup.client.data.remote.refreshAuthTokens
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.ClientIdentity
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Browser upload module: replaces `adminModule`'s Ktor [UploadApiContract] and
 * [ArchiveUploadApiContract] with [BrowserUploadApi] and [BrowserArchiveUploadApi], which send picked
 * files through the browser's own XMLHttpRequest — see [XhrUploadTransport] for why Ktor's JS engine
 * cannot carry an audiobook or a large archive. Loaded after the shared modules, so it wins.
 *
 * The transport reads the same three things the Ktor client does — the active server URL, the
 * factory's access token, and the bearer plugin's refresh bridge ([refreshAuthTokens]) — so there is
 * still one answer to "which server, which credential, and what happens on a 401".
 */
internal val browserUploadModule: Module =
    module {
        single {
            val identity = get<ClientIdentity>()
            XhrUploadTransport(
                serverUrl = { get<ServerConfig>().getActiveUrl()?.value },
                accessToken = { get<ApiClientFactory>().currentAccessToken() },
                refreshAccessToken = {
                    refreshAuthTokens(get()) { get<AuthRepository>().refreshAccessToken() }?.accessToken
                },
                clientHeaders =
                    mapOf(
                        VersionHeaders.CLIENT_VERSION to identity.version,
                        VersionHeaders.CLIENT_API to identity.apiVersion,
                    ),
            )
        }
        single<UploadApiContract> {
            BrowserUploadApi(ktor = UploadApi(clientFactory = get()), transport = get())
        }
        single<ArchiveUploadApiContract> {
            BrowserArchiveUploadApi(ktor = ArchiveUploadApi(clientFactory = get()), transport = get())
        }
    }
