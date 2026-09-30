package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.SwitchField
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Server settings — what this server calls itself, how it is reached from outside, and the two
 * server-wide switches.
 *
 * Pure in [state]; the store wiring lives one level up.
 *
 * **A page of its own, unlike both native clients.** Compose and iOS render these as a section of
 * one long Admin screen. Web does not, for the reason `AdminPage`'s own KDoc gives: eleven admin
 * areas behind a single sidebar entry is a native compromise, not a design, and each area here has
 * its own loading and failure states to show.
 *
 * ⛔ **The text fields and the switches do not save the same way, and the page has to say so.**
 * The name and the URL are an edit buffer — nothing leaves until Save. Each switch is written the
 * moment it moves, optimistically, and reverts if the server refuses. Presenting all four under one
 * Save button would be a lie about two of them, so the switches sit in their own section with the
 * Save button visibly attached only to the fields above it.
 */
@Composable
fun ServerSettingsPage(
    state: AdminSettingsUiState,
    onServerName: (String) -> Unit,
    onRemoteUrl: (String) -> Unit,
    onHoldNewBooks: (Boolean) -> Unit,
    onPushNotifications: (Boolean) -> Unit,
    onSetRatingSourceEnabled: (ExternalRatingSource, Boolean) -> Unit = { _, _ -> },
    onSave: () -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
    onOpenAdmin: () -> Unit,
    nowMs: Long = 0L,
) {
    Div(attrs = { classes("srv") }) {
        Breadcrumb(trail = listOf("Admin", "Server settings"), onNavigate = { onOpenAdmin() })

        PageHeader(title = "Server settings")

        when (state) {
            AdminSettingsUiState.Loading -> {
                Div(attrs = { classes("skel", "srv-skel") })
            }

            is AdminSettingsUiState.Error -> {
                EmptyState(title = "Server settings can't be shown", body = state.error.message) {
                    Button(kind = ButtonKind.Primary, onClick = { onRetry() }) { Text("Try again") }
                }
            }

            is AdminSettingsUiState.Ready -> {
                ReadyContent(
                    state = state,
                    onServerName = onServerName,
                    onRemoteUrl = onRemoteUrl,
                    onHoldNewBooks = onHoldNewBooks,
                    onPushNotifications = onPushNotifications,
                    onSetRatingSourceEnabled = onSetRatingSourceEnabled,
                    onSave = onSave,
                    onClearError = onClearError,
                    nowMs = nowMs,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: AdminSettingsUiState.Ready,
    onServerName: (String) -> Unit,
    onRemoteUrl: (String) -> Unit,
    onHoldNewBooks: (Boolean) -> Unit,
    onPushNotifications: (Boolean) -> Unit,
    onSetRatingSourceEnabled: (ExternalRatingSource, Boolean) -> Unit,
    onSave: () -> Unit,
    onClearError: () -> Unit,
    nowMs: Long,
) {
    // A failed switch has already reverted itself in the ViewModel by the time this renders, so
    // this is the only thing that says the flick did not take.
    state.error?.let { error ->
        Div(attrs = { classes("srv-err") }) {
            P(attrs = {
                classes("srv-err-t")
                attr("role", "alert")
            }) { Text(error.message) }
            Button(attrs = {
                classes("srv-err-x")
                attr("type", VALUE_BUTTON)
                attr("aria-label", "Dismiss")
                onClick { onClearError() }
            }) { Icon(WebIcon.X, size = DISMISS_ICON_SIZE) }
        }
    }

    // A real <form> so Enter in either field saves, which is what a two-field form should do.
    Form(attrs = {
        classes("srv-body")
        onSubmit { event ->
            event.preventDefault()
            onSave()
        }
    }) {
        FormSection(title = "Identity") {
            Hint("What this server calls itself, wherever it introduces itself to a listener.")
            Field(
                label = "Server name",
                value = state.serverName,
                onInput = onServerName,
                id = "srv-name",
            )
            Field(
                label = "Remote URL",
                value = state.remoteUrl,
                onInput = onRemoteUrl,
                placeholder = "https://audiobooks.example.com",
                id = "srv-remote-url",
            )
            Hint(
                "Where this server is reachable from outside your network. Invites are sent with " +
                    "this address, so an empty or wrong one sends people somewhere they cannot reach.",
            )
        }

        Div(attrs = { classes("edit-actions") }) {
            // No onClick: submitting the form is what saves, for click and Enter alike.
            Button(
                kind = ButtonKind.Primary,
                submit = true,
                enabled = !(state.isSaving || !state.isDirty),
            ) { Text(if (state.isSaving) "Saving…" else "Save settings") }
        }
    }

    FormSection(title = "Server-wide") {
        // ⛔ Not inside the form above, and not under its Save. Each of these writes on the flick.
        Hint("These take effect the moment you flick them — there is nothing to save.")
        SwitchRow(
            label = "Hold new books for review",
            description =
                "New books wait in the inbox instead of joining the library. Healthy books wait " +
                    "there too, not just the ones with problems.",
            checked = state.holdNewBooksForReview,
            enabled = !state.isSaving,
            onChange = onHoldNewBooks,
        )
        SwitchRow(
            label = "Push notifications",
            description = "Deliver invites and alerts to devices through the ListenUp relay.",
            checked = state.pushNotificationsEnabled,
            enabled = !state.isSaving,
            onChange = onPushNotifications,
        )
    }

    if (state.ratingSources.isNotEmpty()) {
        FormSection(title = "Rating sources") {
            Hint(
                "Where the score on every book comes from. Switching one off hides its scores; " +
                    "switching it back on brings them back.",
            )
            state.ratingSources.forEach { source ->
                key(source.source) {
                    RatingSourceRow(
                        status = source,
                        nowMs = nowMs,
                        enabled = !state.isSaving,
                        onChange = { enabled -> onSetRatingSourceEnabled(source.source, enabled) },
                    )
                }
            }
        }
    }
}

/**
 * One outside rating source: its name, its switch, and the lines that say how it is doing. The
 * health line reads, first match wins: why it cannot run at all, then until when it has paused
 * itself (a future date, so an absolute one — "Paused until October 6, 2026"), then its last
 * error, then when it last fetched, then that it never has. Hardcover adds a second line naming
 * whose account it fetches with.
 *
 * An unavailable source's switch stays operable: turning it off is still meaningful.
 */
@Composable
private fun RatingSourceRow(
    status: RatingSourceStatus,
    nowMs: Long,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val connectionLine =
        status.connectionUsername
            ?.takeIf { status.source == ExternalRatingSource.HARDCOVER }
            ?.let { "Using $it's Hardcover account" }
    Div(attrs = { classes("srv-toggle") }) {
        Div(attrs = { classes("srv-toggle-d") }) {
            Div { Text(ratingSourceHealth(status, nowMs)) }
            connectionLine?.let { Div { Text(it) } }
        }
        SwitchField(
            label = ratingSourceName(status.source),
            checked = status.enabled,
            onChange = onChange,
            enabled = enabled,
        )
    }
}

/** "Audible", "Hardcover", "Goodreads" — the source name every platform shows. */
private fun ratingSourceName(source: ExternalRatingSource): String =
    when (source) {
        ExternalRatingSource.AUDIBLE -> "Audible"
        ExternalRatingSource.HARDCOVER -> "Hardcover"
        ExternalRatingSource.GOODREADS -> "Goodreads"
        ExternalRatingSource.UNKNOWN -> "Unknown"
    }

/** [status]'s one-line health, in the priority [RatingSourceRow] documents — en.json's `admin.rating_source_*`. */
private fun ratingSourceHealth(
    status: RatingSourceStatus,
    nowMs: Long,
): String {
    val unavailable = status.unavailable
    val pausedUntil = status.pausedUntil
    val lastError = status.lastError
    val lastFetchedAt = status.lastFetchedAt
    return when {
        unavailable != null -> {
            when (unavailable) {
                RatingSourceUnavailable.NOT_CONFIGURED -> "Not set up on this server"
                RatingSourceUnavailable.NO_CONNECTION -> "Connect a Hardcover account to enable"
                RatingSourceUnavailable.UNKNOWN -> "Unavailable on this server"
            }
        }

        pausedUntil != null && lastError != null -> {
            "Paused until ${formatDateLong(pausedUntil)}: $lastError"
        }

        pausedUntil != null -> {
            "Paused until ${formatDateLong(pausedUntil)}"
        }

        lastError != null -> {
            "Last attempt failed: $lastError"
        }

        lastFetchedAt != null -> {
            "Last fetched ${relativeLastActive(lastFetchedAt, nowMs)}"
        }

        else -> {
            "Not fetched yet"
        }
    }
}

/**
 * One server-wide switch, and the sentence that says what flicking it does.
 *
 * The description sits beside the control rather than inside its label: a `<label>` is read out
 * whole when the switch is reached, and a two-sentence label makes every visit to the control a
 * paragraph.
 */
@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Div(attrs = { classes("srv-toggle") }) {
        Span(attrs = { classes("srv-toggle-d") }) { Text(description) }
        SwitchField(label = label, checked = checked, onChange = onChange, enabled = enabled)
    }
}

/** The sentence under a section title that says what the controls below it are for. */
@Composable
private fun Hint(text: String) {
    P(attrs = { classes("srv-hint") }) { Text(text) }
}

/** Every button here is an action, never a form submit. */
private const val VALUE_BUTTON = "button"

private const val DISMISS_ICON_SIZE = 16
