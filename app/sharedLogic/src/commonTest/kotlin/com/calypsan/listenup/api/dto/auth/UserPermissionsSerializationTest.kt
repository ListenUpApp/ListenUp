package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserPermissionsSerializationTest :
    FunSpec({
        test("UserPermissions defaults to all-true") {
            UserPermissions() shouldBe UserPermissions(canEdit = true, canShare = true)
        }
        test("User round-trips with permissions") {
            val user =
                User(
                    id = UserId("u1"),
                    email = "a@b.c",
                    displayName = "A",
                    role = UserRole.MEMBER,
                    status = UserStatus.ACTIVE,
                    createdAt = 0L,
                    permissions = UserPermissions(canEdit = false, canShare = true),
                )
            val decoded = contractJson.decodeFromString<User>(contractJson.encodeToString(user))
            decoded shouldBe user
            decoded.permissions.canEdit shouldBe false
        }
    })
