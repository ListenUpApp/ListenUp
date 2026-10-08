package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.calypsan.listenup.api.dto.auth.User as ContractUser
import com.calypsan.listenup.api.dto.auth.UserPermissions as ContractUserPermissions

/**
 * Pins that the contract user survives the trip into the domain model intact (#1270).
 *
 * The bug this exists to prevent is a silent one. The mapper used to reduce the contract user to
 * `isAdmin`, so `canEdit` never reached the domain model at all — while the server
 * went on enforcing it on every metadata mutation. Nothing threw, nothing logged, and no screen
 * looked wrong; a member simply could not be given edit rights, and there was no way to tell from
 * the client that the flag existed.
 */
class ContractUserMapperTest :
    FunSpec({

        fun contractUser(
            role: UserRole = UserRole.MEMBER,
            canEdit: Boolean = true,
        ) = ContractUser(
            id = UserId("user-1"),
            email = "Reader@Example.com",
            displayName = "Reader",
            role = role,
            status = UserStatus.ACTIVE,
            createdAt = 1_000L,
            permissions = ContractUserPermissions(canEditMetadata = canEdit),
        )

        test("carries the canEdit flag across") {
            val domain = contractUser(canEdit = false).toDomain()

            domain.permissions.canEditMetadata shouldBe false
        }

        test("permissions are independent of the admin bit") {
            // A member with edit rights and an admin without them are both representable.
            // Collapsing permissions into `isAdmin` — which is what the mapper used to do — makes
            // neither expressible.
            val editingMember = contractUser(role = UserRole.MEMBER, canEdit = true).toDomain()
            editingMember.isAdmin shouldBe false
            editingMember.permissions.canEditMetadata shouldBe true

            val restrictedAdmin = contractUser(role = UserRole.ADMIN, canEdit = false).toDomain()
            restrictedAdmin.isAdmin shouldBe true
            restrictedAdmin.permissions.canEditMetadata shouldBe false
        }

        test("ROOT and ADMIN both map to isAdmin") {
            contractUser(role = UserRole.ROOT).toDomain().isAdmin shouldBe true
            contractUser(role = UserRole.ADMIN).toDomain().isAdmin shouldBe true
            contractUser(role = UserRole.MEMBER).toDomain().isAdmin shouldBe false
        }

        test("carries both permission flags across") {
            val domain =
                contractUser()
                    .copy(permissions = ContractUserPermissions(canEditMetadata = false, canCurateLibrary = true))
                    .toDomain()
            domain.permissions.canEditMetadata shouldBe false
            domain.permissions.canCurateLibrary shouldBe true
        }
    })
