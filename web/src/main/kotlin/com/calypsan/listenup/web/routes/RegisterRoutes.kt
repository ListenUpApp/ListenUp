package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.pendingBody
import com.calypsan.listenup.web.html.pendingDeniedFragment
import com.calypsan.listenup.web.html.pendingWaitingFragment
import com.calypsan.listenup.web.html.registerForm
import com.calypsan.listenup.web.html.registerFormFragment
import com.calypsan.listenup.web.html.registrationClosedBody
import com.calypsan.listenup.web.html.respondPage
import com.calypsan.listenup.web.security.newCsrfToken
import com.calypsan.listenup.web.security.webCsrfConfig
import com.calypsan.listenup.web.session.setCsrfCookie
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// Wire values of RegistrationStatusEvent.status (pinned by the contract). The server names the
// pending one locally too; mirror that convention here rather than scattering bare literals.
private const val STATUS_APPROVED = "approved"
private const val STATUS_DENIED = "denied"
private const val HX_REDIRECT = "HX-Redirect"

internal fun Route.registerRoutes(deps: WebDependencies) {
    route("/register") {
        install(CSRF, webCsrfConfig)
        get {
            // Offer the form only when registration isn't CLOSED (spec §4). If serverInfo is
            // unreachable, fail open to the form — the POST is gated server-side regardless.
            val info = deps.loopback.serverInfo()
            val closed = info is AppResult.Success && info.data.registrationPolicy == RegistrationPolicy.CLOSED
            if (closed) {
                call.respondPage(title = "Registration closed", csrfToken = null) { registrationClosedBody() }
                return@get
            }
            val token = newCsrfToken()
            call.setCsrfCookie(token)
            call.respondPage(title = "Register", csrfToken = token) { registerForm() }
        }
        post {
            val request = parseRegisterRequest(call.receiveParameters())
            if (request == null) {
                call.respondText(registerFormFragment(INVALID_REGISTRATION_INPUT), ContentType.Text.Html)
                return@post
            }
            when (val result = deps.loopback.register(request)) {
                is AppResult.Success ->
                    when (val outcome = result.data) {
                        is RegisterResult.Authenticated -> {
                            startWebSession(deps, call, outcome.session)
                            call.response.header(HX_REDIRECT, "/")
                            call.respondText("", ContentType.Text.Html)
                        }
                        is RegisterResult.PendingApproval -> {
                            call.response.header(HX_REDIRECT, "/pending?userId=${outcome.userId.value}")
                            call.respondText("", ContentType.Text.Html)
                        }
                    }
                is AppResult.Failure ->
                    call.respondText(registerFormFragment(result.error.message), ContentType.Text.Html)
            }
        }
    }
    get("/pending") {
        val userId = call.request.queryParameters["userId"].orEmpty()
        call.respondPage(title = "Awaiting approval", csrfToken = null) { pendingBody(userId) }
    }
    get("/pending/status") {
        val userId = call.request.queryParameters["userId"].orEmpty()
        when (val status = deps.loopback.registrationStatus(UserId(userId))) {
            is AppResult.Success ->
                when (status.data.status) {
                    STATUS_APPROVED -> {
                        call.response.header(HX_REDIRECT, "/login")
                        call.respondText("", ContentType.Text.Html)
                    }
                    // Terminal status. `hx-swap="innerHTML"` keeps the #pending-status div (and its
                    // `every 5s` poll + SSE reconnect) alive, so a denied registrant keeps polling
                    // until they navigate away. Benign but untidy — a future sse-close/trigger-stop
                    // fix should land before this pattern is copied to another SSE screen.
                    STATUS_DENIED -> {
                        call.respondText(
                            pendingDeniedFragment(status.data.message ?: "Your registration was denied."),
                            ContentType.Text.Html,
                        )
                    }
                    else -> {
                        call.respondText(pendingWaitingFragment(), ContentType.Text.Html)
                    }
                }
            // Never-Stranded: a transient loopback failure shows the waiting message (the next
            // poll retries), not a dead-end error on a screen whose whole job is to wait.
            is AppResult.Failure ->
                call.respondText(pendingWaitingFragment(), ContentType.Text.Html)
        }
    }
}
