package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.AdminUserService].
 * All routes live under `/api/v1/admin/users` and require an admin (ROOT/ADMIN)
 * JWT; non-admin callers receive Forbidden.
 *
 * RPC is the first-class surface; these resources exist so the same operations
 * are reachable over plain REST for third-party integrations.
 */
@Resource("/api/v1/admin/users")
class AdminUserResources {
    /**
     * REST mirror for the user roster:
     * - `GET /api/v1/admin/users` →
     *   [com.calypsan.listenup.api.AdminUserService.listUsers]
     */
    @Resource("")
    class List(
        val parent: AdminUserResources = AdminUserResources(),
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.AdminUserService.listPendingUsers] —
     * `GET /api/v1/admin/users/pending` returns users awaiting an approval decision.
     */
    @Resource("pending")
    class Pending(
        val parent: AdminUserResources = AdminUserResources(),
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.AdminUserService.searchUsers] —
     * `GET /api/v1/admin/users/search?q=…` matches display name or email.
     */
    @Resource("search")
    class Search(
        val parent: AdminUserResources = AdminUserResources(),
        /** Search query matched against display name and email. */
        val q: String = "",
    )

    /**
     * REST mirror of
     * [com.calypsan.listenup.api.AdminUserService.decidePendingRegistration] —
     * `POST /api/v1/admin/users/pending-decision`
     * (body: [com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision]) approves or
     * denies a PENDING_APPROVAL applicant.
     */
    @Resource("pending-decision")
    class PendingDecision(
        val parent: AdminUserResources = AdminUserResources(),
    )

    /**
     * REST mirror for per-user operations:
     * - `GET /api/v1/admin/users/{id}` →
     *   [com.calypsan.listenup.api.AdminUserService.getUser]
     * - `PATCH /api/v1/admin/users/{id}` (body: `AdminUserPatch`) →
     *   [com.calypsan.listenup.api.AdminUserService.updateUser]
     * - `DELETE /api/v1/admin/users/{id}` →
     *   [com.calypsan.listenup.api.AdminUserService.deleteUser]
     */
    @Resource("{id}")
    class Detail(
        val parent: AdminUserResources = AdminUserResources(),
        /** User id string (UUIDv7 at the storage layer). */
        val id: String,
    )
}

/**
 * REST mirror for the instance-wide registration policy:
 * - `GET /api/v1/admin/settings/registration` →
 *   [com.calypsan.listenup.api.AdminUserService.getRegistrationPolicy]
 * - `PUT /api/v1/admin/settings/registration` (body: `RegistrationPolicy`) →
 *   [com.calypsan.listenup.api.AdminUserService.setRegistrationPolicy]
 */
@Resource("/api/v1/admin/settings/registration")
class RegistrationPolicyResource
