package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteSummary
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.share.ShareLinkCodec
import com.calypsan.listenup.client.share.ShareTarget

internal fun InviteSummary.toInviteInfo(
    serverUrl: String,
    remoteUrl: String?,
): InviteInfo = invite.toInviteInfo(serverUrl, remoteUrl)

internal fun InviteDto.toInviteInfo(
    serverUrl: String,
    remoteUrl: String?,
): InviteInfo =
    InviteInfo(
        id = id.value,
        code = code,
        name = displayName,
        email = email,
        role = role.name,
        expiresAt = expiresAt.toString(),
        claimedAt = claimedAt?.toString(),
        // The shared, copyable invite link is a Universal Link / App Link via the deep-link codec
        // (https://link.listenup.audio/o?t=invite&server=…&code=…) — NOT the old "$serverUrl/invite/$code"
        // plain-server URL, which the server has no route for (it 404s) and which never deep-linked.
        // [remoteUrl] rides along when set so an off-LAN invitee can still connect.
        url = ShareLinkCodec.encode(ShareTarget.Invite(serverUrl = serverUrl, code = code, remoteUrl = remoteUrl)),
        createdAt = createdAt.toString(),
    )
