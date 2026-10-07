package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The shape `UserPermissions` and the patch had before #962, as an older binary decodes them. */
@Serializable
private data class OldPermissions(
    @SerialName("canEdit") val canEdit: Boolean = true,
    @SerialName("canCurateLibrary") val canCurateLibrary: Boolean = false,
)

@Serializable
private data class OldAdminUserPatch(
    @SerialName("permissions") val permissions: OldPermissions? = null,
)

class PermissionsVersionSkewTest :
    FunSpec({
        test("a new client reading an older server's user holds the reading-order permission, its default") {
            contractJson.decodeFromString<UserPermissions>("""{"canEdit":true}""") shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = false, canMakeReadingOrders = true)
        }

        test("an old client reading a new server's user ignores the new flag") {
            val newServer =
                contractJson.encodeToString(UserPermissions(canEditMetadata = false, canMakeReadingOrders = false))
            contractJson.decodeFromString<OldPermissions>(newServer) shouldBe OldPermissions(canEdit = false)
        }

        test("an old admin client's canEdit patch leaves the new flag unchanged on a new server") {
            val oldClient = """{"permissions":{"canEdit":false}}"""
            contractJson.decodeFromString<AdminUserPatch>(oldClient).permissions shouldBe
                UserPermissionsPatch(canEditMetadata = false, canMakeReadingOrders = null)
        }

        test("a new client's reading-order toggle sends only the toggled field") {
            contractJson.encodeToString(
                AdminUserPatch(permissions = UserPermissionsPatch(canMakeReadingOrders = false)),
            ) shouldBe """{"permissions":{"canMakeReadingOrders":false}}"""
        }

        test("a patch naming only the reading-order flag is not empty") {
            UserPermissionsPatch(canMakeReadingOrders = true).isEmpty shouldBe false
        }

        test("a patch changes the reading-order flag alone") {
            UserPermissions(canEditMetadata = true, canCurateLibrary = true)
                .patchedBy(UserPermissionsPatch(canMakeReadingOrders = false)) shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = true, canMakeReadingOrders = false)
        }
    })
