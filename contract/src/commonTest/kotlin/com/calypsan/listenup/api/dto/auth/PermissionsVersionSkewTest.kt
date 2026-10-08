package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The reading-order permission (#962) across version skew, pinned as literal wire text. */
class PermissionsVersionSkewTest :
    FunSpec({
        test("a new client reading an older server's user holds the reading-order permission, its default") {
            contractJson.decodeFromString<UserPermissions>("""{"canEdit":true}""") shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = false, canMakeReadingOrders = true)
        }

        test("an old client reading a new server's user finds canEdit where it always was; the new key is ignorable") {
            val newServer =
                contractJson.encodeToString(UserPermissions(canEditMetadata = false, canMakeReadingOrders = false))
            val wire = contractJson.decodeFromString<JsonObject>(newServer)
            wire.getValue("canEdit").jsonPrimitive.boolean shouldBe false
            contractJson.configuration.ignoreUnknownKeys shouldBe true
        }

        test("an old admin client's canEdit patch leaves the new flag unchanged on a new server") {
            contractJson.decodeFromString<AdminUserPatch>("""{"permissions":{"canEdit":false}}""").permissions shouldBe
                UserPermissionsPatch(canEditMetadata = false, canMakeReadingOrders = null)
        }

        test("a new client's reading-order toggle sends only the toggled field") {
            val wire =
                contractJson.encodeToString(
                    AdminUserPatch(permissions = UserPermissionsPatch(canMakeReadingOrders = false)),
                )
            wire shouldBe """{"permissions":{"canMakeReadingOrders":false}}"""
            contractJson
                .decodeFromString<JsonObject>(wire)
                .getValue("permissions")
                .jsonObject.keys shouldBe
                setOf("canMakeReadingOrders")
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
