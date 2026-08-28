package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Admin — the people on this server, and the decisions an admin makes about them.
 *
 * Deliberately just the people. `AdminViewModel` covers users, approvals, invites, password resets
 * and the registration policy as one hub; collections, categories, the inbox, backups, imports and
 * server settings are separate ViewModels and arrive as their own pages rather than being crammed
 * behind one sidebar entry because native happens to.
 *
 * **Every action here lands on a real account.** Deleting a user and revoking an invite are not the
 * same weight, so each says what it does before it does it rather than sharing one generic prompt.
 */
@Composable
fun AdminPage(
    state: AdminUiState,
    nowMs: Long,
    onApproveUser: (String) -> Unit,
    onDenyUser: (String) -> Unit,
    onDeleteUser: (String) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onDecidePasswordReset: (String, Boolean) -> Unit,
    onDismissResetCode: () -> Unit,
    onSetRegistrationPolicy: (RegistrationPolicy) -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    Div(attrs = { classes("adm") }) {
        H1(attrs = { classes("adm-title") }) { Text("People") }

        when (state) {
            is AdminUiState.Loading -> {
                Div(attrs = { classes("skel", "adm-skel") })
            }

            is AdminUiState.Ready -> {
                ReadyContent(
                    state = state,
                    nowMs = nowMs,
                    onApproveUser = onApproveUser,
                    onDenyUser = onDenyUser,
                    onDeleteUser = onDeleteUser,
                    onRevokeInvite = onRevokeInvite,
                    onDecidePasswordReset = onDecidePasswordReset,
                    onDismissResetCode = onDismissResetCode,
                    onSetRegistrationPolicy = onSetRegistrationPolicy,
                    onClearError = onClearError,
                    onRetry = onRetry,
                )
            }
        }
    }
}

/**
 * The loaded page.
 *
 * Split from [AdminPage] because this screen has five sections and two dialogs, and folding it into
 * the state `when` put the whole thing past its complexity limit — the shape [ShelfEditPage] hit for
 * the same reason.
 */
@Composable
@Suppress("LongParameterList")
private fun ReadyContent(
    state: AdminUiState.Ready,
    nowMs: Long,
    onApproveUser: (String) -> Unit,
    onDenyUser: (String) -> Unit,
    onDeleteUser: (String) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onDecidePasswordReset: (String, Boolean) -> Unit,
    onDismissResetCode: () -> Unit,
    onSetRegistrationPolicy: (RegistrationPolicy) -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
) {
    // Which row a confirm is about, rather than a bare boolean: two dialogs share this screen and
    // each needs to name its subject in the copy.
    var deleting by remember { mutableStateOf<AdminUserInfo?>(null) }
    var revoking by remember { mutableStateOf<InviteInfo?>(null) }

    ErrorBanner(state.error, onRetry, onClearError)

    Section("Who can join") {
        SelectField(
            label = "Registration",
            value = state.registrationPolicy.name,
            options = POLICY_OPTIONS,
            onSelect = { raw -> raw?.let { onSetRegistrationPolicy(policyOf(it)) } },
        )
    }

    PendingSection(state, onApproveUser, onDenyUser)
    ResetsSection(state, nowMs, onDecidePasswordReset)
    MembersSection(state) { deleting = it }
    InvitesSection(state) { revoking = it }

    ConfirmDialog(
        open = deleting != null,
        title = "Remove ${deleting?.displayName ?: deleting?.email ?: "this person"}?",
        // Names what survives and what does not, because "delete user" alone leaves the important
        // question — what happens to what they listened to — unanswered.
        body =
            "They lose access to this server immediately. Their listening history goes with them; " +
                "the books stay in the library.",
        confirmLabel = "Remove",
        onConfirm = {
            deleting?.let { onDeleteUser(it.id) }
            deleting = null
        },
        onDismiss = { deleting = null },
    )

    ConfirmDialog(
        open = revoking != null,
        title = "Revoke this invite?",
        // Deliberately lighter than removing a person: nobody has used it yet, and the copy should
        // not make an undo-able tidy-up feel like the same act.
        body = "The link stops working. You can always send ${revoking?.email ?: "them"} another.",
        confirmLabel = "Revoke invite",
        onConfirm = {
            revoking?.let { onRevokeInvite(it.id) }
            revoking = null
        },
        onDismiss = { revoking = null },
    )

    ResetCodeDialog(
        code = state.resetCodeToConvey,
        recipientName = state.resetCodeRecipientName,
        onDismiss = onDismissResetCode,
    )
}

/**
 * A load that failed, above the half that worked.
 *
 * There is no Error state: a failed load still arrives as Ready, carrying `error` and whatever it
 * did fetch. So this sits above the sections rather than replacing them — a half-loaded page that
 * says which half is missing beats a blank one that says only that something went wrong.
 */
@Composable
private fun ErrorBanner(
    message: String?,
    onRetry: () -> Unit,
    onClearError: () -> Unit,
) {
    if (message == null) return

    Div(attrs = { classes("adm-error") }) {
        Span(attrs = { classes("adm-error-t") }) { Text(message) }
        Button(attrs = {
            classes(QUIET_BUTTON)
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { onRetry() }
        }) { Text("Try again") }
        Button(attrs = {
            classes(QUIET_BUTTON)
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { onClearError() }
        }) { Text("Dismiss") }
    }
}

/** People who asked to join. Approving is not confirmed — see the spec that says why. */
@Composable
private fun PendingSection(
    state: AdminUiState.Ready,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    if (state.pendingUsers.isEmpty()) return

    Section("Waiting for you") {
        state.pendingUsers.forEach { user ->
            PersonRow(user, subtitle = user.email) {
                Button(attrs = {
                    classes("btn")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    if (state.approvingUserId == user.id) attr(ATTR_DISABLED, "")
                    onClick { onApprove(user.id) }
                }) { Text(if (state.approvingUserId == user.id) "Approving…" else "Approve") }
                Button(attrs = {
                    classes(QUIET_BUTTON)
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    if (state.denyingUserId == user.id) attr(ATTR_DISABLED, "")
                    onClick { onDeny(user.id) }
                }) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun ResetsSection(
    state: AdminUiState.Ready,
    nowMs: Long,
    onDecide: (String, Boolean) -> Unit,
) {
    if (state.pendingPasswordResets.isEmpty()) return

    Section("Password reset requests") {
        state.pendingPasswordResets.forEach { request ->
            ResetRow(request, nowMs, state.decidingPasswordResetId == request.id, onDecide)
        }
    }
}

@Composable
private fun MembersSection(
    state: AdminUiState.Ready,
    onAskRemove: (AdminUserInfo) -> Unit,
) {
    Section("Members") {
        if (state.users.isEmpty()) {
            P(attrs = { classes("adm-none") }) { Text("Nobody has joined yet.") }
            return@Section
        }
        state.users.forEach { user ->
            PersonRow(user, subtitle = user.email) {
                // The root account is the server's own owner; removing it would leave nobody able
                // to administer anything, so it is not offered rather than refused.
                if (!user.isRoot) {
                    Button(attrs = {
                        classes(QUIET_BUTTON)
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        attr("aria-label", "Remove ${user.displayName ?: user.email}")
                        if (state.deletingUserId == user.id) attr(ATTR_DISABLED, "")
                        onClick { onAskRemove(user) }
                    }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun InvitesSection(
    state: AdminUiState.Ready,
    onAskRevoke: (InviteInfo) -> Unit,
) {
    if (state.pendingInvites.isEmpty()) return

    Section("Open invites") {
        state.pendingInvites.forEach { invite ->
            Div(attrs = { classes("adm-row") }) {
                Div(attrs = { classes("adm-row-text") }) {
                    Span(attrs = { classes("adm-row-t") }) { Text(invite.name) }
                    Span(attrs = { classes("adm-row-sub") }) { Text(invite.email) }
                }
                Div(attrs = { classes("adm-row-actions") }) {
                    Button(attrs = {
                        classes(QUIET_BUTTON)
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        attr("aria-label", "Revoke the invite for ${invite.email}")
                        if (state.revokingInviteId == invite.id) attr(ATTR_DISABLED, "")
                        onClick { onAskRevoke(invite) }
                    }) { Text("Revoke") }
                }
            }
        }
    }
}

/**
 * The one-time code an approved reset produces.
 *
 * Shown once, by design: the ViewModel surfaces it exactly once so an admin can convey it out of
 * band, and dismissing is what discards it. Rendered as its own dialog rather than inline so it
 * cannot sit on a shared screen after the conversation that needed it — a credential left visible
 * is a credential shared with whoever walks past.
 */
@Composable
private fun ResetCodeDialog(
    code: String?,
    recipientName: String?,
    onDismiss: () -> Unit,
) {
    if (code == null) return

    ConfirmDialog(
        open = true,
        title = "Reset code for ${recipientName ?: "this person"}",
        body = "Read this to them yourself, not over email: $code — it is shown once and cannot be shown again.",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ResetRow(
    request: PasswordResetRequest,
    nowMs: Long,
    deciding: Boolean,
    onDecide: (String, Boolean) -> Unit,
) {
    Div(attrs = { classes("adm-row") }) {
        Div(attrs = { classes("adm-row-text") }) {
            Span(attrs = { classes("adm-row-t") }) { Text(request.displayName) }
            Span(attrs = { classes("adm-row-sub") }) { Text(request.email) }
            Span(attrs = { classes("adm-row-when") }) { Text(relativeLastActive(request.requestedAt, nowMs)) }
        }
        Div(attrs = { classes("adm-row-actions") }) {
            Button(attrs = {
                classes("btn")
                attr(ATTR_TYPE, VALUE_BUTTON)
                if (deciding) attr(ATTR_DISABLED, "")
                onClick { onDecide(request.id, true) }
            }) { Text("Approve") }
            Button(attrs = {
                classes(QUIET_BUTTON)
                attr(ATTR_TYPE, VALUE_BUTTON)
                if (deciding) attr(ATTR_DISABLED, "")
                onClick { onDecide(request.id, false) }
            }) { Text("Decline") }
        }
    }
}

@Composable
private fun PersonRow(
    user: AdminUserInfo,
    subtitle: String,
    actions: @Composable () -> Unit,
) {
    Div(attrs = { classes("adm-row") }) {
        Div(attrs = { classes("adm-row-text") }) {
            Span(attrs = { classes("adm-row-t") }) { Text(user.displayName ?: user.email) }
            Span(attrs = { classes("adm-row-sub") }) { Text(subtitle) }
            if (user.isRoot) {
                Span(attrs = { classes("adm-badge") }) { Text("Owner") }
            }
        }
        Div(attrs = { classes("adm-row-actions") }) { actions() }
    }
}

@Composable
private fun Section(
    heading: String,
    content: @Composable () -> Unit,
) {
    Div(attrs = { classes("adm-section") }) {
        H2(attrs = { classes("adm-section-h") }) { Text(heading) }
        content()
    }
}

/** The policies, worded as what they mean rather than as their enum names. */
private val POLICY_OPTIONS =
    listOf(
        SelectOption(RegistrationPolicy.OPEN.name, "Anyone can join"),
        SelectOption(RegistrationPolicy.APPROVAL_QUEUE.name, "Anyone can ask, you approve"),
        SelectOption(RegistrationPolicy.CLOSED.name, "Invite only"),
    )

/** An unrecognised value closes the door rather than opening it. */
internal fun policyOf(raw: String): RegistrationPolicy =
    RegistrationPolicy.entries.firstOrNull { it.name == raw } ?: RegistrationPolicy.CLOSED

/** The outline button — every action here that is not the affirmative one. */
private const val QUIET_BUTTON = "btn-o"

private const val ATTR_DISABLED = "disabled"

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"
