package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AdminUserPatchSerializationTest :
    FunSpec({
        test("AdminUserPatch round-trips with role and a permissions patch set") {
            val patch =
                AdminUserPatch(
                    displayName = "New Name",
                    role = UserRole.ADMIN,
                    permissions = UserPermissionsPatch(canEdit = false),
                )
            contractJson.decodeFromString<AdminUserPatch>(contractJson.encodeToString(patch)) shouldBe patch
        }

        test("all-null AdminUserPatch round-trips") {
            val decoded = contractJson.decodeFromString<AdminUserPatch>(contractJson.encodeToString(AdminUserPatch()))
            decoded shouldBe AdminUserPatch()
            decoded.permissions shouldBe null
        }

        test("an older admin client's whole-flags patch decodes with every other flag unchanged (null)") {
            val decoded = contractJson.decodeFromString<AdminUserPatch>("""{"permissions":{"canEdit":false}}""")
            decoded.permissions shouldBe UserPermissionsPatch(canEdit = false)
        }

        test("a patch toggling one flag sends only that flag, so an old server never sees the others") {
            contractJson.encodeToString(AdminUserPatch(permissions = UserPermissionsPatch(canEdit = true))) shouldBe
                """{"permissions":{"canEdit":true}}"""
        }
    })
