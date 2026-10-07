package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserPermissionsSerializationTest :
    FunSpec({
        test("defaults: canEdit and canContributeStoryWorld on, canCurateStoryWorld off") {
            UserPermissions() shouldBe
                UserPermissions(canEdit = true, canContributeStoryWorld = true, canCurateStoryWorld = false)
        }

        test("a user from a server older than Story World decodes with the Story World defaults") {
            val decoded = contractJson.decodeFromString<UserPermissions>("""{"canEdit":false}""")
            decoded shouldBe UserPermissions(canEdit = false, canContributeStoryWorld = true, canCurateStoryWorld = false)
        }

        test("UserPermissionsPatch carries the Story World flags independently") {
            val patch = UserPermissionsPatch(canCurateStoryWorld = true)
            val json = contractJson.encodeToString(patch)
            json shouldBe """{"canCurateStoryWorld":true}"""
            contractJson.decodeFromString<UserPermissionsPatch>(json) shouldBe patch
        }

        test("ServerInfo from an older server says it has no Story World") {
            val legacy =
                """{"name":"L","version":"1","apiVersion":"v1","setupRequired":false,"registrationPolicy":"OPEN","instanceId":"i"}"""
            contractJson.decodeFromString<com.calypsan.listenup.api.dto.ServerInfo>(legacy).storyWorld shouldBe false
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
                    permissions = UserPermissions(canEdit = false),
                )
            val decoded = contractJson.decodeFromString<User>(contractJson.encodeToString(user))
            decoded shouldBe user
            decoded.permissions.canEdit shouldBe false
        }
    })
