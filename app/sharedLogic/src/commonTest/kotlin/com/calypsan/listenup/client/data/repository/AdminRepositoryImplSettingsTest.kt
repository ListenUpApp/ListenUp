package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.admin.AdminServerSettings
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.IOException

private class FakeAdminSettingsService : AdminSettingsService {
    var stored = AdminServerSettings("ListenUp", null, inboxEnabled = false)
    var lastPatch: AdminServerSettingsPatch? = null

    override suspend fun getServerSettings() = AppResult.Success(stored)

    override suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings> {
        lastPatch = patch
        stored = AdminServerSettings(patch.serverName ?: stored.serverName, patch.remoteUrl ?: stored.remoteUrl, inboxEnabled = patch.inboxEnabled ?: stored.inboxEnabled)
        return AppResult.Success(stored)
    }
}

class AdminRepositoryImplSettingsTest :
    FunSpec({
        fun repo(svc: AdminSettingsService) =
            AdminRepositoryImpl(
                adminUserChannel = RpcChannel.forTest(mock<AdminUserService>()),
                adminSettingsChannel = RpcChannel.forTest(svc),
                inviteAdminChannel = RpcChannel.forTest(mock<InviteService>()),
                libraryAdminChannel = RpcChannel.forTest(mock<LibraryAdminService>()),
                serverConfig = mock(),
                adminUserRosterDao = mock(),
            )

        test("getServerSettings maps the RPC DTO to the domain model") {
            val svc = FakeAdminSettingsService().apply { stored = AdminServerSettings("My Lib", "https://x", inboxEnabled = true) }
            (repo(svc).getServerSettings() as AppResult.Success).data shouldBe
                com.calypsan.listenup.client.domain.model
                    .ServerSettings("My Lib", "https://x", inboxEnabled = true)
        }

        test("updateServerSettings forwards a patch and returns the new settings") {
            val svc = FakeAdminSettingsService()
            (repo(svc).updateServerSettings(serverName = "Renamed") as AppResult.Success).data.serverName shouldBe "Renamed"
            svc.lastPatch?.serverName shouldBe "Renamed"
        }

        test("a transport throw becomes a typed Failure, not a throw") {
            // The service throw is a transport-level fault; the channel folds it through ErrorMapper into
            // a TYPED TransportError (an IOException → NetworkUnavailable), not a blanket InternalError.
            val throwing =
                object : AdminSettingsService {
                    override suspend fun getServerSettings(): AppResult<AdminServerSettings> = throw IOException("network down")

                    override suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings> = throw IOException("network down")
                }
            repo(throwing)
                .getServerSettings()
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<TransportError.NetworkUnavailable>()
        }
    })
