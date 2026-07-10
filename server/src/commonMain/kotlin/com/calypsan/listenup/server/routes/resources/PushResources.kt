package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.PushService]. All routes live under
 * `/api/v1/push` and require JWT authentication.
 */
@Resource("/api/v1/push")
class PushResources {
    /**
     * REST mirror for the token-registry collection:
     * - `POST /api/v1/push/tokens` (body: `RegisterPushTokenBody`) →
     *   [com.calypsan.listenup.api.PushService.registerToken]
     * - `DELETE /api/v1/push/tokens?token=...` →
     *   [com.calypsan.listenup.api.PushService.unregisterToken]
     */
    @Resource("tokens")
    class Tokens(
        val parent: PushResources = PushResources(),
        /** Token to unregister (DELETE only; POST reads its token from the request body). */
        val token: String? = null,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.PushService.sendTestNotification] —
     * `POST /api/v1/push/test`. Rate-limited.
     */
    @Resource("test")
    class Test(
        val parent: PushResources = PushResources(),
    )
}
