package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.api.dto.profile.AvatarUploadResponse
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A client that predates framing: it knows only the avatar version. */
@Serializable
@SerialName("AvatarUploadResponse")
private data class PreFramingAvatarUploadResponse(
    @SerialName("avatarUpdatedAt") val avatarUpdatedAt: Long,
)

private val framing = ImageFraming(imageVersion = "abc", frame = ImageFrame(0.5, 0.25, 1.75))

private fun <T> roundTrip(
    serializer: KSerializer<T>,
    value: T,
): T = contractJson.decodeFromString(serializer, contractJson.encodeToString(serializer, value))

/** [value] as a sender that predates [key] would have written it. */
private fun <T> without(
    serializer: KSerializer<T>,
    value: T,
    key: String,
): T = contractJson.decodeFromJsonElement(serializer, JsonObject(contractJson.encodeToJsonElement(serializer, value).jsonObject - key))

class ImageFrameFieldsContractTest :
    FunSpec({
        val contributor =
            ContributorSyncPayload(
                id = "c1",
                name = "Ray Porter",
                sortName = null,
                revision = 1,
                updatedAt = 1,
                createdAt = 1,
                deletedAt = null,
                imagePath = "contributors/abc.jpg",
                imageFrame = framing,
            )
        val profile =
            PublicProfileSyncPayload(
                id = "u1",
                displayName = "Ada",
                avatarType = "image",
                tagline = null,
                totalSecondsAllTime = 0,
                totalSecondsLast7Days = 0,
                totalSecondsLast30Days = 0,
                totalSecondsLast365Days = 0,
                booksFinished = 0,
                currentStreakDays = 0,
                longestStreakDays = 0,
                revision = 1,
                updatedAt = 1,
                createdAt = 1,
                deletedAt = null,
                avatarUpdatedAt = 7,
                avatarFrame = framing,
            )

        test("every payload carries its frame across the wire") {
            roundTrip(ContributorSyncPayload.serializer(), contributor) shouldBe contributor
            roundTrip(PublicProfileSyncPayload.serializer(), profile) shouldBe profile
            roundTrip(ContributorUpdate.serializer(), ContributorUpdate(imageFrame = framing)).imageFrame shouldBe framing
            roundTrip(UpdateProfileRequest.serializer(), UpdateProfileRequest(avatarFrame = framing)).avatarFrame shouldBe framing
            roundTrip(AvatarUploadResponse.serializer(), AvatarUploadResponse(avatarUpdatedAt = 7, avatarFrame = framing)) shouldBe
                AvatarUploadResponse(avatarUpdatedAt = 7, avatarFrame = framing)
        }

        test("a sender that predates framing decodes as no frame") {
            without(ContributorSyncPayload.serializer(), contributor, "imageFrame").imageFrame.shouldBeNull()
            without(PublicProfileSyncPayload.serializer(), profile, "avatarFrame").avatarFrame.shouldBeNull()
            without(ContributorUpdate.serializer(), ContributorUpdate(imageFrame = framing), "imageFrame").imageFrame.shouldBeNull()
            without(
                UpdateProfileRequest.serializer(),
                UpdateProfileRequest(avatarFrame = framing),
                "avatarFrame",
            ).avatarFrame.shouldBeNull()
            without(AvatarUploadResponse.serializer(), AvatarUploadResponse(7, framing), "avatarFrame").avatarFrame.shouldBeNull()
        }

        test("a receiver that predates framing ignores the field (contractJson ignores unknown keys)") {
            val json = contractJson.encodeToString(AvatarUploadResponse.serializer(), AvatarUploadResponse(7, framing))
            contractJson.decodeFromString(PreFramingAvatarUploadResponse.serializer(), json).avatarUpdatedAt shouldBe 7
        }
    })
