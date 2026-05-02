package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST resource mirror of AuthService. The server's REST routing block
 * dispatches these against the same DTOs and handlers used by RPC. The
 * OpenAPI spec generates from this surface.
 */
@Resource("/api/v1/auth")
class AuthResources {
    @Resource("login")
    class Login(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("register")
    class Register(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("setup")
    class Setup(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("refresh")
    class Refresh(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("logout")
    class Logout(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("logout/all")
    class LogoutAll(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("current-user")
    class CurrentUser(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("sessions")
    class Sessions(
        val parent: AuthResources = AuthResources(),
    )

    @Resource("pending-registrations/{userId}/decision")
    class DecidePendingRegistration(
        val userId: String,
        val parent: AuthResources = AuthResources(),
    )
}
