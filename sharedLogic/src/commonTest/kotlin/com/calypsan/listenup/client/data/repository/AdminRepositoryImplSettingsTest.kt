package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.dto.admin.AdminServerSettings
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.AdminSettingsRpcFactory
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class FakeAdminSettingsService : AdminSettingsService {
    var stored = AdminServerSettings("ListenUp", null, inboxEnabled = false, pushNotificationsEnabled = true)
    var lastPatch: AdminServerSettingsPatch? = null

    override suspend fun getServerSettings() = AppResult.Success(stored)

    override suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings> {
        lastPatch = patch
        stored =
            AdminServerSettings(
                patch.serverName ?: stored.serverName,
                patch.remoteUrl ?: stored.remoteUrl,
                inboxEnabled = patch.inboxEnabled ?: stored.inboxEnabled,
                pushNotificationsEnabled = patch.pushNotificationsEnabled ?: stored.pushNotificationsEnabled,
            )
        return AppResult.Success(stored)
    }
}

private class FakeAdminSettingsRpcFactory(
    private val s: AdminSettingsService,
) : AdminSettingsRpcFactory {
    override suspend fun get() = s

    override suspend fun invalidate() = Unit
}

class AdminRepositoryImplSettingsTest :
    FunSpec({
        fun repo(svc: AdminSettingsService) =
            AdminRepositoryImpl(
                adminUserRpc = mock(),
                adminSettingsRpc = FakeAdminSettingsRpcFactory(svc),
                inviteRpc = mock(),
                libraryAdminRpc = mock(),
                serverConfig = mock(),
                adminUserRosterDao = mock(),
            )

        test("getServerSettings maps the RPC DTO to the domain model") {
            val svc =
                FakeAdminSettingsService().apply {
                    stored = AdminServerSettings("My Lib", "https://x", inboxEnabled = true, pushNotificationsEnabled = true)
                }
            (repo(svc).getServerSettings() as AppResult.Success).data shouldBe
                com.calypsan.listenup.client.domain.model
                    .ServerSettings("My Lib", "https://x", inboxEnabled = true)
        }

        test("updateServerSettings forwards a patch and returns the new settings") {
            val svc = FakeAdminSettingsService()
            (repo(svc).updateServerSettings(serverName = "Renamed") as AppResult.Success).data.serverName shouldBe "Renamed"
            svc.lastPatch?.serverName shouldBe "Renamed"
        }

        test("a transport throw becomes Failure, not a throw") {
            val throwing =
                object : AdminSettingsRpcFactory {
                    override suspend fun get(): AdminSettingsService = throw RuntimeException("WS 401")

                    override suspend fun invalidate() = Unit
                }
            val r = AdminRepositoryImpl(mock(), throwing, mock(), mock(), mock(), mock()).getServerSettings()
            (r is AppResult.Failure) shouldBe true
        }
    })
