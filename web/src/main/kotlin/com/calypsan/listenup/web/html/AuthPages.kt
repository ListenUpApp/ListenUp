package com.calypsan.listenup.web.html

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.MAIN
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.stream.createHTML

/**
 * Render a full HTML page via [appShell], supplying body content through [block].
 * When [csrfToken] is non-null the shell embeds the CSRF meta tag and htmx hook.
 */
suspend fun ApplicationCall.respondPage(
    title: String,
    csrfToken: String? = null,
    block: MAIN.() -> Unit,
) = respondHtml { appShell(title, csrfToken) { block() } }

/**
 * Render the login form **inside** the caller's [FlowContent] scope. Produces a
 * `div#auth-form` containing: a heading, an optional error paragraph, and an htmx form
 * that POSTs to `/login` and swaps itself (`outerHTML`) on response.
 */
fun FlowContent.loginForm(
    email: String = "",
    error: String? = null,
) = authFormDiv(email, error)

/**
 * Serialise the `div#auth-form` element to an HTML string suitable for returning as an
 * htmx fragment response. The root element is the `div#auth-form` itself — no outer
 * wrapper — so htmx's `hx-swap="outerHTML"` on `#auth-form` replaces it cleanly.
 */
fun loginFormFragment(
    email: String,
    error: String?,
): String =
    createHTML().div {
        id = "auth-form"
        authFormContent(email, error)
    }

// ---------------------------------------------------------------------------
// Private shared builders
// ---------------------------------------------------------------------------

/** Emits `div#auth-form { … }` into any [FlowContent] scope. */
private fun FlowContent.authFormDiv(
    email: String,
    error: String?,
) = div {
    id = "auth-form"
    authFormContent(email, error)
}

/**
 * The interior of `div#auth-form`: heading, optional error, and the htmx form.
 * Called from both [authFormDiv] and [loginFormFragment] so the markup is always
 * identical regardless of the render path.
 */
private fun DIV.authFormContent(
    email: String,
    error: String?,
) {
    h1 { +"Sign in" }
    if (error != null) {
        p(classes = "text-red-600") { +error }
    }
    form {
        attributes["hx-post"] = "/login"
        attributes["hx-target"] = "#auth-form"
        attributes["hx-swap"] = "outerHTML"
        label { attributes["for"] = "login-email"; +"Email" }
        input(type = InputType.email, name = "email") {
            id = "login-email"
            value = email
        }
        label { attributes["for"] = "login-password"; +"Password" }
        input(type = InputType.password, name = "password") {
            id = "login-password"
        }
        button(type = ButtonType.submit) { +"Sign in" }
    }
}

// ---------------------------------------------------------------------------
// Setup form (first-run owner account creation)
// ---------------------------------------------------------------------------

/**
 * Render the setup form **inside** the caller's [FlowContent] scope. Produces a
 * `div#auth-form` containing: a heading, an optional error paragraph, and an htmx form
 * that POSTs to `/setup` and swaps itself (`outerHTML`) on response.
 */
fun FlowContent.setupForm(error: String? = null) = setupFormDiv(error)

/**
 * Serialise the `div#auth-form` element to an HTML string suitable for returning as an
 * htmx fragment response. The root element is the `div#auth-form` itself — no outer
 * wrapper — so htmx's `hx-swap="outerHTML"` on `#auth-form` replaces it cleanly.
 */
fun setupFormFragment(error: String?): String =
    createHTML().div {
        id = "auth-form"
        setupFormContent(error)
    }

/** Emits `div#auth-form { … }` for the setup form into any [FlowContent] scope. */
private fun FlowContent.setupFormDiv(error: String?) = div {
    id = "auth-form"
    setupFormContent(error)
}

/**
 * The interior of `div#auth-form` for the first-run setup screen: heading, optional error,
 * and the htmx form with displayName, email, and password fields.
 * Called from both [setupFormDiv] and [setupFormFragment] so the markup is always identical.
 */
private fun DIV.setupFormContent(error: String?) {
    h1 { +"Welcome — create the owner account" }
    if (error != null) {
        p(classes = "text-red-600") { +error }
    }
    form {
        attributes["hx-post"] = "/setup"
        attributes["hx-target"] = "#auth-form"
        attributes["hx-swap"] = "outerHTML"
        label { attributes["for"] = "setup-display-name"; +"Display name" }
        input(type = InputType.text, name = "displayName") {
            id = "setup-display-name"
        }
        label { attributes["for"] = "setup-email"; +"Email" }
        input(type = InputType.email, name = "email") {
            id = "setup-email"
        }
        label { attributes["for"] = "setup-password"; +"Password" }
        input(type = InputType.password, name = "password") {
            id = "setup-password"
        }
        button(type = ButtonType.submit) { +"Create account" }
    }
}
