package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.loginForm
import com.calypsan.listenup.web.html.loginFormFragment
import com.calypsan.listenup.web.html.respondPage
import com.calypsan.listenup.web.html.setupForm
import com.calypsan.listenup.web.html.setupFormFragment
import com.calypsan.listenup.web.security.newCsrfToken
import com.calypsan.listenup.web.security.webCsrfConfig
import com.calypsan.listenup.web.session.setCsrfCookie
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.loginRoutes(deps: WebDependencies) {
    route("/login") {
        install(CSRF, webCsrfConfig)
        get {
            val token = newCsrfToken()
            call.setCsrfCookie(token)
            call.respondPage(title = "Sign in", csrfToken = token) { loginForm() }
        }
        post {
            val params = call.receiveParameters()
            val email = params["email"].orEmpty()
            val password = params["password"].orEmpty()
            // LoginRequest validates password length; treat any validation failure as invalid
            // credentials to avoid leaking policy details on the login form.
            val request =
                runCatching { LoginRequest(email, password) }.getOrNull()
                    ?: run {
                        call.respondText(
                            loginFormFragment(email = email, error = AuthError.InvalidCredentials().message),
                            ContentType.Text.Html,
                        )
                        return@post
                    }
            when (val result = deps.loopback.login(request)) {
                is AppResult.Success -> {
                    startWebSession(deps, call, result.data)
                    call.response.header("HX-Redirect", "/")
                    call.respondText("", ContentType.Text.Html)
                }
                is AppResult.Failure ->
                    call.respondText(
                        loginFormFragment(email = email, error = result.error.message),
                        ContentType.Text.Html,
                    )
            }
        }
    }

    route("/setup") {
        install(CSRF, webCsrfConfig)
        get {
            when (val info = deps.loopback.serverInfo()) {
                is AppResult.Success ->
                    if (!info.data.setupRequired) {
                        call.respondRedirect("/login")
                    } else {
                        val token = newCsrfToken()
                        call.setCsrfCookie(token)
                        call.respondPage(title = "Set up ListenUp", csrfToken = token) { setupForm() }
                    }
                is AppResult.Failure -> call.respondRedirect("/login")
            }
        }
        post {
            val request = parseRegisterRequest(call.receiveParameters())
            if (request == null) {
                call.respondText(setupFormFragment(INVALID_REGISTRATION_INPUT), ContentType.Text.Html)
                return@post
            }
            when (val result = deps.loopback.setup(request)) {
                is AppResult.Success -> {
                    startWebSession(deps, call, result.data)
                    call.response.header("HX-Redirect", "/")
                    call.respondText("", ContentType.Text.Html)
                }
                is AppResult.Failure ->
                    call.respondText(setupFormFragment(result.error.message), ContentType.Text.Html)
            }
        }
    }
}
