package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.admin.AdminServerSettings
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.IOException

private class FakeAdminSettingsService : AdminSettingsService {
    var stored = AdminServerSettings("ListenUp", null, holdNewBooksForReview = false, pushNotificationsEnabled = true)
    var lastPatch: AdminServerSettingsPatch? = null

    override suspend fun getServerSettings() = AppResult.Success(stored)

    override suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings> {
        lastPatch = patch
        stored =
            AdminServerSettings(
                patch.serverName ?: stored.serverName,
                patch.remoteUrl ?: stored.remoteUrl,
                holdNewBooksForReview = patch.holdNewBooksForReview ?: stored.holdNewBooksForReview,
                pushNotificationsEnabled = patch.pushNotificationsEnabled ?: stored.pushNotificationsEnabled,
            )
        return AppResult.Success(stored)
    }

    var ratingSources =
        listOf(RatingSourceStatus(ExternalRatingSource.AUDIBLE, enabled = true, lastFetchedAt = 1L, lastError = null))

    override suspend fun getRatingSources(): AppResult<List<RatingSourceStatus>> = AppResult.Success(ratingSources)

    override suspend fun setRatingSourceEnabled(
        source: ExternalRatingSource,
        enabled: Boolean,
    ): AppResult<List<RatingSourceStatus>> {
        ratingSources = ratingSources.map { if (it.source == source) it.copy(enabled = enabled) else it }
        return AppResult.Success(ratingSources)
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
            val svc =
                FakeAdminSettingsService().apply {
                    stored = AdminServerSettings("My Lib", "https://x", holdNewBooksForReview = true, pushNotificationsEnabled = true)
                }
            (repo(svc).getServerSettings() as AppResult.Success).data shouldBe
                com.calypsan.listenup.client.domain.model
                    .ServerSettings("My Lib", "https://x", holdNewBooksForReview = true)
        }

        test("updateServerSettings forwards a patch and returns the new settings") {
            val svc = FakeAdminSettingsService()
            (repo(svc).updateServerSettings(serverName = "Renamed") as AppResult.Success).data.serverName shouldBe "Renamed"
            svc.lastPatch?.serverName shouldBe "Renamed"
        }

        test("getServerSettings maps pushNotificationsEnabled through to the domain model") {
            val svc =
                FakeAdminSettingsService().apply {
                    stored = AdminServerSettings("Lib", null, holdNewBooksForReview = false, pushNotificationsEnabled = false)
                }
            (repo(svc).getServerSettings() as AppResult.Success).data.pushNotificationsEnabled shouldBe false
        }

        test("updateServerSettings forwards pushNotificationsEnabled and returns the new settings") {
            val svc = FakeAdminSettingsService()
            (
                repo(svc).updateServerSettings(pushNotificationsEnabled = false) as AppResult.Success
            ).data.pushNotificationsEnabled shouldBe false
            svc.lastPatch?.pushNotificationsEnabled shouldBe false
        }

        test("a transport throw becomes a typed Failure, not a throw") {
            // The service throw is a transport-level fault; the channel folds it through ErrorMapper into
            // a TYPED TransportError (an IOException → NetworkUnavailable), not a blanket InternalError.
            val throwing =
                object : AdminSettingsService {
                    override suspend fun getServerSettings(): AppResult<AdminServerSettings> = throw IOException("network down")

                    override suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings> =
                        throw IOException("network down")

                    override suspend fun getRatingSources(): AppResult<List<RatingSourceStatus>> = throw IOException("network down")

                    override suspend fun setRatingSourceEnabled(
                        source: ExternalRatingSource,
                        enabled: Boolean,
                    ): AppResult<List<RatingSourceStatus>> = throw IOException("network down")
                }
            repo(throwing)
                .getServerSettings()
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<TransportError.NetworkUnavailable>()
        }

        test("getRatingSources returns every source's status") {
            val svc = FakeAdminSettingsService()
            (repo(svc).getRatingSources() as AppResult.Success).data shouldBe svc.ratingSources
        }

        test("setRatingSourceEnabled forwards the toggle and returns the new list") {
            val svc = FakeAdminSettingsService()
            val result = repo(svc).setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, false) as AppResult.Success
            result.data.single().enabled shouldBe false
            svc.ratingSources.single().enabled shouldBe false
        }
    })
