package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AdminUserPatchSerializationTest :
    FunSpec({
        test("AdminUserPatch round-trips with role and permissions set") {
            val patch =
                AdminUserPatch(
                    displayName = "New Name",
                    role = UserRole.ADMIN,
                    permissions = UserPermissions(canEdit = false, canShare = true),
                )
            val decoded =
                contractJson.decodeFromString<AdminUserPatch>(contractJson.encodeToString(patch))
            decoded shouldBe patch
        }
        test("all-null AdminUserPatch round-trips") {
            val patch = AdminUserPatch()
            val decoded =
                contractJson.decodeFromString<AdminUserPatch>(contractJson.encodeToString(patch))
            decoded shouldBe patch
            decoded.displayName shouldBe null
            decoded.role shouldBe null
            decoded.permissions shouldBe null
        }
    })
