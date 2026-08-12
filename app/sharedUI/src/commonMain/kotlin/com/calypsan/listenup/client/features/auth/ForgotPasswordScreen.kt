package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.features.auth.components.AuthBadge
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.features.auth.components.AuthStepRow
import com.calypsan.listenup.client.features.auth.components.AuthStepState
import com.calypsan.listenup.client.features.auth.components.CodeBoxes
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_check_status
import listenup.composeapp.generated.resources.auth_checking_automatically
import listenup.composeapp.generated.resources.auth_forgot_password_attempts
import listenup.composeapp.generated.resources.auth_forgot_password_attempts_one
import listenup.composeapp.generated.resources.auth_forgot_password_awaiting
import listenup.composeapp.generated.resources.auth_forgot_password_complete
import listenup.composeapp.generated.resources.auth_forgot_password_denied
import listenup.composeapp.generated.resources.auth_forgot_password_enter_code
import listenup.composeapp.generated.resources.auth_forgot_password_explainer
import listenup.composeapp.generated.resources.auth_forgot_password_how_it_works
import listenup.composeapp.generated.resources.auth_forgot_password_retry
import listenup.composeapp.generated.resources.auth_forgot_password_reveal_hint
import listenup.composeapp.generated.resources.auth_forgot_password_send_request
import listenup.composeapp.generated.resources.auth_forgot_password_step_code
import listenup.composeapp.generated.resources.auth_forgot_password_step_finish
import listenup.composeapp.generated.resources.auth_forgot_password_step_request
import listenup.composeapp.generated.resources.auth_forgot_password_step_approve
import listenup.composeapp.generated.resources.auth_forgot_password_step_approve_sub
import listenup.composeapp.generated.resources.auth_forgot_password_step_sent
import listenup.composeapp.generated.resources.auth_forgot_password_step_set
import listenup.composeapp.generated.resources.auth_forgot_password_step_set_sub
import listenup.composeapp.generated.resources.auth_forgot_password_survives
import listenup.composeapp.generated.resources.auth_forgot_password_ticket
import listenup.composeapp.generated.resources.auth_forgot_password_title
import listenup.composeapp.generated.resources.auth_password_label
import listenup.composeapp.generated.resources.auth_pending_review
import listenup.composeapp.generated.resources.common_continue
import listenup.composeapp.generated.resources.common_email
import listenup.composeapp.generated.resources.common_something_went_wrong
import listenup.composeapp.generated.resources.common_try_again
import listenup.composeapp.generated.resources.setup_back_to_sign_in
import org.jetbrains.compose.resources.stringResource

/**
 * Screen for the forgot-password flow: request a reset, wait for an admin, then complete with
 * the out-of-band code the admin conveys.
 *
 * Renders through the shared [AuthScaffold] like every other auth screen. One composable per
 * [ForgotPasswordUiState] arm — split out as [ForgotPasswordContent] so it can be rendered and
 * tested without a live [ForgotPasswordViewModel], mirroring `PendingApprovalContent`.
 *
 * There is a single manual fallback, [ForgotPasswordViewModel.checkStatus], surfaced as the
 * "Check Status" button on [ForgotPasswordUiState.AwaitingApproval] — the never-stranded
 * counterpart to the automatic poll/stream watch, exactly as `PendingApprovalScreen` does for
 * registration.
 */
@Composable
fun ForgotPasswordScreen(
    viewModel: ForgotPasswordViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ForgotPasswordContent(
        state = state,
        onBack = onBack,
        onRequestReset = viewModel::requestReset,
        onCheckStatus = viewModel::checkStatus,
        onCompleteReset = viewModel::completeReset,
        onRetry = viewModel::retryRequest,
        modifier = modifier,
    )
}

/**
 * Stateless visual for the forgot-password screen — split out so it can be previewed and
 * screenshotted without a live [ForgotPasswordViewModel].
 */
@Composable
internal fun ForgotPasswordContent(
    state: ForgotPasswordUiState,
    onBack: () -> Unit,
    onRequestReset: (email: String) -> Unit,
    onCheckStatus: () -> Unit,
    onCompleteReset: (code: String, newPassword: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ForgotPasswordUiState.EnterEmail -> {
            EnterEmailContent(onBack = onBack, onSubmit = onRequestReset, modifier = modifier)
        }

        is ForgotPasswordUiState.Submitting -> {
            FullScreenLoadingIndicator(modifier = modifier)
        }

        is ForgotPasswordUiState.AwaitingApproval -> {
            AwaitingApprovalContent(
                ticketId = state.ticketId,
                onCheckStatus = onCheckStatus,
                modifier = modifier,
            )
        }

        is ForgotPasswordUiState.EnterCode -> {
            EnterCodeContent(state = state, onSubmit = onCompleteReset, modifier = modifier)
        }

        ForgotPasswordUiState.Denied -> {
            DeniedContent(onRetry = onRetry, onBackToSignIn = onBack, modifier = modifier)
        }

        ForgotPasswordUiState.Complete -> {
            CompleteContent(onBackToSignIn = onBack, modifier = modifier)
        }

        is ForgotPasswordUiState.Error -> {
            ErrorContent(message = state.message, onBack = onBack, modifier = modifier)
        }
    }
}

/** Initial step — collects the account's email address. */
@Composable
private fun EnterEmailContent(
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier,
) {
    var email by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    fun submit() {
        focusManager.clearFocus()
        if (email.isNotBlank()) onSubmit(email.trim())
    }

    AuthScaffold(
        title = stringResource(Res.string.auth_forgot_password_title),
        subtitle = stringResource(Res.string.auth_forgot_password_explainer),
        onBack = onBack,
        modifier = modifier,
    ) {
        ListenUpTextField(
            value = email,
            onValueChange = { email = it },
            label = stringResource(Res.string.common_email),
            leadingIcon = Icons.Outlined.MailOutline,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        HowItWorks()
        ListenUpButton(
            text = stringResource(Res.string.auth_forgot_password_send_request),
            onClick = { submit() },
            enabled = email.isNotBlank(),
            leadingIcon = Icons.AutoMirrored.Outlined.Send,
        )
    }
}

/**
 * The three steps, stated before the request is sent.
 *
 * This is the single biggest thing the screen was missing: a self-hosted server has no mail
 * transport, so without this the user waits at a screen with no explanation and is then asked for
 * a code that has never been mentioned. No admin is named — this screen is reachable by anyone who
 * is signed out, and no account has been matched to the typed email yet.
 */
@Composable
private fun HowItWorks() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(HOW_IT_WORKS_PADDING),
        verticalArrangement = Arrangement.spacedBy(STEP_SPACING),
    ) {
        Text(
            text = stringResource(Res.string.auth_forgot_password_how_it_works),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            Res.string.auth_forgot_password_step_request,
            Res.string.auth_forgot_password_step_code,
            Res.string.auth_forgot_password_step_finish,
        ).forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(STEP_SPACING)) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(step),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Waiting for an admin. The manual "Check Status" button is the never-stranded fallback.
 *
 * The ticket number is shown because the request outlives the screen — the device claim is
 * persisted, so quoting a number to the person you are asking is useful, and knowing the request
 * survives is what stops someone sitting on this screen waiting.
 *
 * No admin is named here. Several people may hold the role, and this is the one state a request
 * for an unrecognised address also reaches — naming someone would separate real accounts from
 * unknown ones at a glance.
 */
@Composable
private fun AwaitingApprovalContent(
    ticketId: String,
    onCheckStatus: () -> Unit,
    modifier: Modifier,
) {
    AuthScaffold(
        title = stringResource(Res.string.auth_forgot_password_title),
        subtitle = stringResource(Res.string.auth_forgot_password_awaiting),
        badge = AuthBadge(Icons.Outlined.Schedule, stringResource(Res.string.auth_pending_review)),
        modifier = modifier,
    ) {
        ResetTimeline(ticketId)
        Text(
            text = stringResource(Res.string.auth_forgot_password_survives),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(Res.string.auth_checking_automatically),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ListenUpButton(
            text = stringResource(Res.string.auth_check_status),
            onClick = onCheckStatus,
            leadingIcon = Icons.Outlined.Refresh,
        )
    }
}

/**
 * Approved. Collects the out-of-band code plus the new password.
 *
 * The code field accepts the code with or without its dash — the server's `normalize()` strips
 * everything outside `[0-9A-Z]`, so this screen does not need to reproduce that logic, only avoid
 * rejecting input the server will happily accept.
 *
 * The ViewModel does not clear [ForgotPasswordUiState.EnterCode.error] when the user edits the
 * code — `errorDismissed` does that locally, so a stale "wrong code" message never sits under
 * fresh input. It re-arms (via the `remember` keys) whenever the ViewModel hands back a genuinely
 * new error or attempt count.
 */
@Composable
private fun EnterCodeContent(
    state: ForgotPasswordUiState.EnterCode,
    onSubmit: (code: String, newPassword: String) -> Unit,
    modifier: Modifier,
) {
    var code by remember(state.ticketId) { mutableStateOf("") }
    var newPassword by remember(state.ticketId) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorDismissed by remember(state.error, state.attemptsRemaining) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val displayedError = if (errorDismissed) null else state.error

    fun submit() {
        focusManager.clearFocus()
        if (code.isNotBlank() && newPassword.isNotBlank()) onSubmit(code, newPassword)
    }

    AuthScaffold(
        title = stringResource(Res.string.auth_forgot_password_title),
        subtitle = stringResource(Res.string.auth_forgot_password_enter_code),
        modifier = modifier,
    ) {
        CodeBoxes(
            value = code,
            onValueChange = {
                code = it
                errorDismissed = true
            },
            isError = displayedError != null,
            onDone = { focusManager.moveFocus(FocusDirection.Down) },
        )
        displayedError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ListenUpTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = stringResource(Res.string.auth_password_label),
            trailingIcon = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            onTrailingClick = { passwordVisible = !passwordVisible },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Text(
            text = stringResource(Res.string.auth_forgot_password_reveal_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AttemptsRemaining(state.attemptsRemaining)
        ListenUpButton(
            text = stringResource(Res.string.common_continue),
            onClick = { submit() },
            enabled = code.isNotBlank() && newPassword.isNotBlank(),
        )
    }
}

/**
 * Where the request has got to, in the same three-step vocabulary the registration waiting room
 * uses — the two are the same situation (a human is deciding something), so they should read as
 * the same app rather than two unrelated waiting screens.
 */
@Composable
private fun ResetTimeline(ticketId: String) {
    // The raw ticket id is `<uuid>.<issuedAtMs>.<sig>` — an internal blob. Show only the first
    // UUID segment as the human-scale reference; it's plenty to correlate with the admin's list.
    val ticketReference = ticketId.substringBefore('.').substringBefore('-').uppercase()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        AuthStepRow(
            state = AuthStepState.DONE,
            icon = Icons.AutoMirrored.Outlined.Send,
            title = stringResource(Res.string.auth_forgot_password_step_sent),
            subtitle = stringResource(Res.string.auth_forgot_password_ticket, ticketReference),
        )
        AuthStepRow(
            state = AuthStepState.ACTIVE,
            icon = Icons.Outlined.Schedule,
            title = stringResource(Res.string.auth_forgot_password_step_approve),
            subtitle = stringResource(Res.string.auth_forgot_password_step_approve_sub),
        )
        AuthStepRow(
            state = AuthStepState.TODO,
            icon = Icons.Outlined.Lock,
            title = stringResource(Res.string.auth_forgot_password_step_set),
            subtitle = stringResource(Res.string.auth_forgot_password_step_set_sub),
        )
    }
}

/**
 * The terminal mark — the scalloped shape the rest of the app uses for people and medallions,
 * rather than a plain circle. Tone carries the outcome: an error container for a decline, the
 * tertiary container for a success, so the two terminals are distinguishable at a glance and
 * before any copy is read.
 */
@Composable
private fun TerminalMark(
    icon: ImageVector,
    container: Color,
    ink: Color,
) {
    Box(
        modifier = Modifier.size(TERMINAL_MARK_SIZE).clip(cookieScallopShape()).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(TERMINAL_ICON_SIZE),
        )
    }
}

/**
 * The remaining attempt budget, stated only once it is worth stating.
 *
 * The previous version painted every count in the error colour, which spends the alarm long before
 * it means anything — by the time one attempt is left, the styling has nothing louder to say. A
 * comfortable budget says nothing at all; a shrinking one is a plain note; the last one is an
 * error, and only then is it worth explaining what running out costs.
 */
@Composable
private fun AttemptsRemaining(remaining: Int?) {
    if (remaining == null || remaining >= ATTEMPTS_WORTH_MENTIONING) return
    val isLast = remaining <= 1
    val container =
        if (isLast) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val ink =
        if (isLast) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(container)
                .padding(ATTEMPTS_PADDING),
        horizontalArrangement = Arrangement.spacedBy(STEP_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isLast) Icons.Outlined.Warning else Icons.Outlined.Info,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(ATTEMPTS_ICON_SIZE),
        )
        Text(
            text =
                if (isLast) {
                    stringResource(Res.string.auth_forgot_password_attempts_one)
                } else {
                    stringResource(Res.string.auth_forgot_password_attempts, remaining)
                },
            style = MaterialTheme.typography.labelLarge,
            color = ink,
        )
    }
}

/**
 * Declined, but not a dead end.
 *
 * A decline is usually a misunderstanding rather than a verdict — the person approving may simply
 * not have recognised the request. "Ask again" re-opens it, so it reappears in the admin's queue
 * without the requester having to find their way back through the flow from sign-in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeniedContent(
    onRetry: () -> Unit,
    onBackToSignIn: () -> Unit,
    modifier: Modifier,
) {
    AuthScaffold(
        title = stringResource(Res.string.auth_forgot_password_title),
        subtitle = stringResource(Res.string.auth_forgot_password_denied),
        modifier = modifier,
    ) {
        TerminalMark(
            icon = Icons.Outlined.Cancel,
            container = MaterialTheme.colorScheme.errorContainer,
            ink = MaterialTheme.colorScheme.onErrorContainer,
        )
        // The connected pair the comp draws: one choice with two halves, using M3's own connected
        // corner tokens. The expressive `ButtonGroup` container itself is still alpha and renders
        // no reachable label under the render tests, so the shapes come from it and the layout
        // does not — the figure is the same, and it stays verifiable.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            OutlinedButton(
                onClick = onBackToSignIn,
                shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                modifier = Modifier.weight(1f).height(PAIR_HEIGHT),
            ) {
                Text(stringResource(Res.string.setup_back_to_sign_in))
            }
            Button(
                onClick = onRetry,
                shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                modifier = Modifier.weight(RETRY_WEIGHT).height(PAIR_HEIGHT),
            ) {
                Text(stringResource(Res.string.auth_forgot_password_retry))
            }
        }
    }
}

/** Done — the one screen in this flow that gets to be purely good news. */
@Composable
private fun CompleteContent(
    onBackToSignIn: () -> Unit,
    modifier: Modifier,
) {
    AuthScaffold(
        title = stringResource(Res.string.auth_forgot_password_title),
        subtitle = stringResource(Res.string.auth_forgot_password_complete),
        modifier = modifier,
    ) {
        TerminalMark(
            icon = Icons.Outlined.CheckCircle,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            ink = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ListenUpButton(
            text = stringResource(Res.string.setup_back_to_sign_in),
            onClick = onBackToSignIn,
            leadingIcon = Icons.AutoMirrored.Outlined.Login,
        )
    }
}

/** Terminal failure — a transport/stream error, or an expired request. */
@Composable
private fun ErrorContent(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    AuthScaffold(
        title = stringResource(Res.string.common_something_went_wrong),
        subtitle = message,
        modifier = modifier,
    ) {
        ListenUpButton(
            text = stringResource(Res.string.common_try_again),
            onClick = onBack,
        )
    }
}

/** Below this, the remaining budget is comfortable enough not to be worth saying. */
private const val ATTEMPTS_WORTH_MENTIONING = 4

private val HOW_IT_WORKS_PADDING = 16.dp

private val STEP_SPACING = 12.dp

private val TERMINAL_MARK_SIZE = 96.dp

private val TERMINAL_ICON_SIZE = 40.dp

private val ATTEMPTS_PADDING = 14.dp

private val ATTEMPTS_ICON_SIZE = 20.dp

/** The way forward is asking again, so it carries the wider half of the pair. */
private const val RETRY_WEIGHT = 2f

private val PAIR_HEIGHT = 56.dp
