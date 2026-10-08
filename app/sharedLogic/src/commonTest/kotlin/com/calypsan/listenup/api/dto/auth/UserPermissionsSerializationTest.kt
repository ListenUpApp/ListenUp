package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserPermissionsSerializationTest :
    FunSpec({
        test("defaults follow the rule: edit metadata on, curate library off") {
            UserPermissions() shouldBe UserPermissions(canEditMetadata = true, canCurateLibrary = false)
        }

        test("canEditMetadata keeps the wire name canEdit") {
            contractJson.encodeToString(UserPermissions(canEditMetadata = false)) shouldBe """{"canEdit":false}"""
        }

        test("a user from a server older than the split decodes with curate library off") {
            contractJson.decodeFromString<UserPermissions>("""{"canEdit":true}""") shouldBe
                UserPermissions(canEditMetadata = true, canCurateLibrary = false)
        }

        test("User round-trips with both flags") {
            val user =
                User(
                    id = UserId("u1"),
                    email = "a@b.c",
                    displayName = "A",
                    role = UserRole.MEMBER,
                    status = UserStatus.ACTIVE,
                    createdAt = 0L,
                    permissions = UserPermissions(canEditMetadata = false, canCurateLibrary = true),
                )
            contractJson.decodeFromString<User>(contractJson.encodeToString(user)) shouldBe user
        }
    })
