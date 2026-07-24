package com.calypsan.listenup.api.dto.invite

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InviteDtoSerializationTest :
    FunSpec({
        test("InviteDto round-trips") {
            val dto =
                InviteDto(
                    id = InviteId("i1"),
                    code = "abc",
                    email = "a@b.c",
                    displayName = "A",
                    role = UserRole.MEMBER,
                    createdBy = "admin1",
                    expiresAt = 100L,
                    createdAt = 0L,
                    claimedAt = null,
                    claimedBy = null,
                )
            contractJson.decodeFromString<InviteDto>(contractJson.encodeToString(dto)) shouldBe dto
        }
        test("InvitePreview round-trips") {
            val p =
                InvitePreview(
                    displayName = "A",
                    email = "a@b.c",
                    invitedByName = "Root",
                    serverName = "MyServer",
                    valid = true,
                    invalidReason = null,
                )
            contractJson.decodeFromString<InvitePreview>(contractJson.encodeToString(p)) shouldBe p
        }
    })
