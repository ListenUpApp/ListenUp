package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteSummary
import com.calypsan.listenup.client.domain.model.InviteInfo

internal fun InviteSummary.toInviteInfo(serverUrl: String): InviteInfo = invite.toInviteInfo(serverUrl)

internal fun InviteDto.toInviteInfo(serverUrl: String): InviteInfo =
    InviteInfo(
        id = id.value,
        code = code,
        name = displayName,
        email = email,
        role = role.name,
        expiresAt = expiresAt.toString(),
        claimedAt = claimedAt?.toString(),
        url = "$serverUrl/invite/$code",
        createdAt = createdAt.toString(),
    )
