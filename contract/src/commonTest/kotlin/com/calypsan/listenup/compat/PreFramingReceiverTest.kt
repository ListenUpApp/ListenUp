package com.calypsan.listenup.compat

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.api.dto.profile.AvatarUploadResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A client that predates framing: it knows only the avatar version. Lives outside the `api` package because it is a
 * test stand-in for an old build, not a contract type (ArchitectureTest keeps every `api` DTO in commonMain).
 */
@Serializable
@SerialName("AvatarUploadResponse")
private data class PreFramingAvatarUploadResponse(
    @SerialName("avatarUpdatedAt") val avatarUpdatedAt: Long,
)

/** An older receiver reads a framed payload, because the shared JSON ignores keys it does not know. */
class PreFramingReceiverTest :
    FunSpec({
        test("a receiver that predates framing ignores the field (contractJson ignores unknown keys)") {
            val framing = ImageFraming(imageVersion = "abc", frame = ImageFrame(0.5, 0.25, 1.75))
            val json = contractJson.encodeToString(AvatarUploadResponse.serializer(), AvatarUploadResponse(7, framing))

            contractJson.decodeFromString(PreFramingAvatarUploadResponse.serializer(), json).avatarUpdatedAt shouldBe 7
        }
    })
