package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.registerForm
import com.calypsan.listenup.web.html.registerFormFragment
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

internal fun Route.registerRoutes(deps: WebDependencies) {
    route("/register") {
        install(CSRF, webCsrfConfig)
        get {
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
                            call.response.header("HX-Redirect", "/")
                            call.respondText("", ContentType.Text.Html)
                        }
                        is RegisterResult.PendingApproval -> {
                            call.response.header("HX-Redirect", "/pending?userId=${outcome.userId.value}")
                            call.respondText("", ContentType.Text.Html)
                        }
                    }
                is AppResult.Failure ->
                    call.respondText(registerFormFragment(result.error.message), ContentType.Text.Html)
            }
        }
    }
}
