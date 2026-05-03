package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST resource mirror of AuthService. The server's REST routing block
 * dispatches these against the same DTOs and handlers used by RPC. The
 * OpenAPI spec generates from this surface. Consumed by ktor-server-resources
 * route handlers in `:server`; also by ktor-client-resources for typed URL
 * construction on the client side.
 */
@Resource("/api/v1/auth")
class AuthResources {
    /** REST endpoint for AuthService.login — POST /api/v1/auth/login. */
    @Resource("login")
    class Login(
        val parent: AuthResources = AuthResources(),
    )

    /** REST endpoint for AuthService.register — POST /api/v1/auth/register. */
    @Resource("register")
    class Register(
        val parent: AuthResources = AuthResources(),
    )

    /** REST endpoint for AuthService.setupRoot — POST /api/v1/auth/setup. */
    @Resource("setup")
    class Setup(
        val parent: AuthResources = AuthResources(),
    )

    /** REST endpoint for AuthService.refreshSession — POST /api/v1/auth/refresh. */
    @Resource("refresh")
    class Refresh(
        val parent: AuthResources = AuthResources(),
    )

    /** REST endpoint for AuthService.logout — POST /api/v1/auth/logout. Authenticated. */
    @Resource("logout")
    class Logout(
        val parent: AuthResources = AuthResources(),
    )

    /** REST endpoint for AuthService.logoutAll — POST /api/v1/auth/logout/all. Authenticated. */
    @Resource("logout/all")
    class LogoutAll(
        val parent: AuthResources = AuthResources(),
    )

    /** REST endpoint for AuthService.currentUser — GET /api/v1/auth/current-user. Authenticated. */
    @Resource("current-user")
    class CurrentUser(
        val parent: AuthResources = AuthResources(),
    )

    /** REST endpoint for AuthService.listSessions — GET /api/v1/auth/sessions. Authenticated. */
    @Resource("sessions")
    class Sessions(
        val parent: AuthResources = AuthResources(),
    )

    /**
     * REST endpoint for AuthServiceAuthed.decidePendingRegistration —
     * POST /api/v1/auth/pending-registrations/decision. Admin/root only.
     *
     * The user id lives in the request body, not the URL — single source of
     * truth and a less REST-idiomatic shape that makes this surface mirror the
     * RPC contract more directly.
     */
    @Resource("pending-registrations/decision")
    class DecidePendingRegistration(
        val parent: AuthResources = AuthResources(),
    )
}
