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

// ---------------------------------------------------------------------------
// Shared field-list-driven form builder
// ---------------------------------------------------------------------------

/** One field in an auth form. */
private data class AuthField(
    val label: String,
    val name: String,
    val type: InputType,
    val id: String,
    val value: String = "",
)

/**
 * Emits the shared interior of `div#auth-form`: heading, optional error paragraph,
 * and an htmx form that POSTs to [action] and swaps itself (`outerHTML`) on response.
 */
private fun DIV.authFormContent(
    heading: String,
    action: String,
    submitLabel: String,
    error: String?,
    fields: List<AuthField>,
) {
    h1 { +heading }
    if (error != null) {
        p(classes = "text-red-600") { +error }
    }
    form {
        attributes["hx-post"] = action
        attributes["hx-target"] = "#auth-form"
        attributes["hx-swap"] = "outerHTML"
        fields.forEach { f ->
            label { attributes["for"] = f.id; +f.label }
            input(type = f.type, name = f.name) {
                id = f.id
                if (f.value.isNotEmpty()) value = f.value
            }
        }
        button(type = ButtonType.submit) { +submitLabel }
    }
}

/** Emits `div#auth-form { … }` into any [FlowContent] scope. */
private fun FlowContent.authFormDiv(
    heading: String,
    action: String,
    submitLabel: String,
    error: String?,
    fields: List<AuthField>,
) = div {
    id = "auth-form"
    authFormContent(heading, action, submitLabel, error, fields)
}

/**
 * Serialises the `div#auth-form` element to an HTML string suitable for returning as an
 * htmx fragment response. The root element is the `div#auth-form` itself — no outer
 * wrapper — so htmx's `hx-swap="outerHTML"` on `#auth-form` replaces it cleanly.
 */
private fun authFormFragment(
    heading: String,
    action: String,
    submitLabel: String,
    error: String?,
    fields: List<AuthField>,
): String =
    createHTML().div {
        id = "auth-form"
        authFormContent(heading, action, submitLabel, error, fields)
    }

// ---------------------------------------------------------------------------
// Login form
// ---------------------------------------------------------------------------

private fun loginFields(email: String): List<AuthField> =
    listOf(
        AuthField("Email", "email", InputType.email, "login-email", value = email),
        AuthField("Password", "password", InputType.password, "login-password"),
    )

/**
 * Render the login form **inside** the caller's [FlowContent] scope. Produces a
 * `div#auth-form` containing: a heading, an optional error paragraph, and an htmx form
 * that POSTs to `/login` and swaps itself (`outerHTML`) on response.
 */
fun FlowContent.loginForm(
    email: String = "",
    error: String? = null,
) = authFormDiv("Sign in", "/login", "Sign in", error, loginFields(email))

/** htmx fragment for the login form. See [authFormFragment]. */
fun loginFormFragment(
    email: String,
    error: String?,
): String = authFormFragment("Sign in", "/login", "Sign in", error, loginFields(email))

// ---------------------------------------------------------------------------
// Setup form (first-run owner account creation)
// ---------------------------------------------------------------------------

private val setupFields: List<AuthField> =
    listOf(
        AuthField("Display name", "displayName", InputType.text, "setup-display-name"),
        AuthField("Email", "email", InputType.email, "setup-email"),
        AuthField("Password", "password", InputType.password, "setup-password"),
    )

/**
 * Render the setup form **inside** the caller's [FlowContent] scope. Produces a
 * `div#auth-form` containing: a heading, an optional error paragraph, and an htmx form
 * that POSTs to `/setup` and swaps itself (`outerHTML`) on response.
 */
fun FlowContent.setupForm(error: String? = null) =
    authFormDiv("Welcome — create the owner account", "/setup", "Create account", error, setupFields)

/** htmx fragment for the setup form. See [authFormFragment]. */
fun setupFormFragment(error: String?): String =
    authFormFragment("Welcome — create the owner account", "/setup", "Create account", error, setupFields)

// ---------------------------------------------------------------------------
// Register form (open / invitation-based registration)
// ---------------------------------------------------------------------------

private val registerFields: List<AuthField> =
    listOf(
        AuthField("Display name", "displayName", InputType.text, "register-display-name"),
        AuthField("Email", "email", InputType.email, "register-email"),
        AuthField("Password", "password", InputType.password, "register-password"),
    )

/**
 * Render the register form **inside** the caller's [FlowContent] scope. Produces a
 * `div#auth-form` containing: a heading, an optional error paragraph, and an htmx form
 * that POSTs to `/register` and swaps itself (`outerHTML`) on response.
 */
fun FlowContent.registerForm(error: String? = null) =
    authFormDiv("Create your account", "/register", "Register", error, registerFields)

/** htmx fragment for the register form. See [authFormFragment]. */
fun registerFormFragment(error: String?): String =
    authFormFragment("Create your account", "/register", "Register", error, registerFields)

// ---------------------------------------------------------------------------
// Pending-approval waiting room
// ---------------------------------------------------------------------------

/**
 * The pending-approval waiting room. Real-time: the htmx SSE extension subscribes directly
 * to the public, same-origin registration-status stream. Never-Stranded fallback: poll the
 * BFF's own status endpoint. Both retarget `#pending-status`, which swaps in the
 * pending/denied fragment (an `HX-Redirect` on approval navigates away).
 */
fun FlowContent.pendingBody(userId: String) {
    div {
        attributes["hx-ext"] = "sse"
        attributes["sse-connect"] = "/api/v1/auth/registration-status/$userId/stream"
        h1 { +"Almost there" }
        div {
            id = "pending-status"
            attributes["hx-get"] = "/pending/status?userId=$userId"
            attributes["hx-trigger"] = "sse:message, every 5s"
            attributes["hx-swap"] = "innerHTML"
            +PENDING_WAITING_MESSAGE
        }
    }
}

/** Fragment swapped into `#pending-status` while the account is still pending. */
fun pendingWaitingFragment(): String = createHTML().p { +PENDING_WAITING_MESSAGE }

/** Fragment swapped into `#pending-status` when the registration was denied. */
fun pendingDeniedFragment(reason: String): String =
    createHTML().p(classes = "text-red-600") { +reason }

private const val PENDING_WAITING_MESSAGE = "Your account is awaiting administrator approval…"
