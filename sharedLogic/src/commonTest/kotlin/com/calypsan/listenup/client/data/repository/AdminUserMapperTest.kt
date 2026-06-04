package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AdminUserMapperTest :
    FunSpec({
        fun user(
            role: UserRole = UserRole.MEMBER,
            displayName: String = "Ada",
        ) = User(
            id = UserId("u1"),
            email = "ada@x",
            displayName = displayName,
            role = role,
            status = UserStatus.ACTIVE,
            createdAt = 1_700_000_000_000L,
            permissions = UserPermissions(canEdit = true, canShare = false),
        )

        test("maps contract User to AdminUserInfo with isRoot true for ROOT") {
            val info = user(role = UserRole.ROOT).toAdminUserInfo()
            info.id shouldBe "u1"
            info.email shouldBe "ada@x"
            info.displayName shouldBe "Ada"
            info.isRoot shouldBe true
            info.role shouldBe "ROOT"
            info.status shouldBe "ACTIVE"
            info.createdAt shouldBe "1700000000000"
            info.permissions.canShare shouldBe false
            info.firstName shouldBe null
            info.lastName shouldBe null
        }

        test("isRoot is false for non-ROOT and displayableName falls back to displayName") {
            val info = user(role = UserRole.MEMBER, displayName = "Ada").toAdminUserInfo()
            info.isRoot shouldBe false
            info.displayableName shouldBe "Ada"
        }
    })
