package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AdminUserPatchSerializationTest :
    FunSpec({
        test("AdminUserPatch round-trips with a role and a permissions patch") {
            val patch =
                AdminUserPatch(
                    displayName = "New Name",
                    role = UserRole.ADMIN,
                    permissions = UserPermissionsPatch(canEditMetadata = false),
                )
            contractJson.decodeFromString<AdminUserPatch>(contractJson.encodeToString(patch)) shouldBe patch
        }

        test("all-null AdminUserPatch round-trips") {
            val decoded = contractJson.decodeFromString<AdminUserPatch>(contractJson.encodeToString(AdminUserPatch()))
            decoded shouldBe AdminUserPatch()
            decoded.permissions shouldBe null
        }

        test("an older admin client's whole-flags patch reads as a patch of canEdit alone") {
            contractJson.decodeFromString<AdminUserPatch>("""{"permissions":{"canEdit":false}}""").permissions shouldBe
                UserPermissionsPatch(canEditMetadata = false)
        }

        test("toggling one flag sends only that flag, so an older server never sees the others") {
            contractJson.encodeToString(AdminUserPatch(permissions = UserPermissionsPatch(canEditMetadata = true))) shouldBe
                """{"permissions":{"canEdit":true}}"""
        }
    })
