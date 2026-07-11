package com.calypsan.listenup.server.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.server.push.PushNotifier

/**
 * Sends a [PushPayload.CampfireInvite] to every explicitly invited user when a campfire is
 * created — the design spec §7 "Invite → push notification with deep link" rule. Push is purely
 * an accelerant: the room is discoverable via `CampfireService.listOpenSessions()` (once invited,
 * per [com.calypsan.listenup.server.api.CampfireServiceImpl]'s invite-only exclusion rule)
 * regardless of whether the push ever arrives, so a dropped notification never strands an invitee
 * (Never Stranded).
 *
 * A thin, separate class over [PushNotifier] rather than inline calls in
 * [com.calypsan.listenup.server.api.CampfireServiceImpl] — a dedicated seam is easy to fake in
 * tests without standing up the whole service. [PushNotifier.notify] never throws by its own
 * contract, so this class has nothing to catch.
 */
class CampfireInviteNotifier(
    private val pushNotifier: PushNotifier,
) {
    /** Notifies every id in [invitedUserIds], skipping [inviterUserId] even if it appears there. */
    suspend fun notifyInvited(
        campfireId: CampfireId,
        bookId: String,
        inviterUserId: String,
        invitedUserIds: List<String>,
    ) {
        invitedUserIds
            .filter { it != inviterUserId }
            .forEach { userId ->
                pushNotifier.notify(
                    userId = userId,
                    payload =
                        PushPayload.CampfireInvite(
                            campfireId = campfireId.value,
                            bookId = bookId,
                            inviterUserId = inviterUserId,
                        ),
                )
            }
    }
}
