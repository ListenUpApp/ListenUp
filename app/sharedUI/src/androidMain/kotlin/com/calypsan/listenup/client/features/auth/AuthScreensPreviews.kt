package com.calypsan.listenup.client.features.auth

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.auth.components.AuthBadge
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_create_account
import listenup.composeapp.generated.resources.auth_create_admin_account
import listenup.composeapp.generated.resources.auth_server_administrator
import listenup.composeapp.generated.resources.auth_set_up_your_listenup_server
import listenup.composeapp.generated.resources.auth_sign_in
import listenup.composeapp.generated.resources.auth_sign_in_to_access_your

/**
 * Design previews for the auth surface. Each screen body is exercised through the shared
 * [AuthScaffold] at a phone width (hero layout) and a desktop width (split layout), in light and
 * dark, against the static fallback palette (`dynamicColor = false`) so the designed coral scheme
 * is what renders rather than a Material You sample. Copy comes from the real string resources, so
 * previews track shipped wording rather than a divergent hardcoded copy.
 */
private const val PHONE_WIDTH = 412
private const val PHONE_HEIGHT = 900
private const val DESK_WIDTH = 1100
private const val DESK_HEIGHT = 760

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

@Composable
private fun SignInPreviewBody() {
    AuthScaffold(
        title = stringResource(Res.string.auth_sign_in),
        subtitle = stringResource(Res.string.auth_sign_in_to_access_your),
    ) {
        LoginFields(state = LoginUiState.Idle, onSubmit = { _, _ -> })
        LoginFooter(openRegistration = true, onRegister = {}, onChangeServer = {})
    }
}

@Composable
private fun CreateAdminPreviewBody(onBack: (() -> Unit)? = null) {
    AuthScaffold(
        title = stringResource(Res.string.auth_create_admin_account),
        subtitle = stringResource(Res.string.auth_set_up_your_listenup_server),
        badge = AuthBadge(Icons.Outlined.AdminPanelSettings, stringResource(Res.string.auth_server_administrator)),
        onBack = onBack,
    ) {
        CreateAccountFields(
            isLoading = false,
            validationField = null,
            submitLabel = stringResource(Res.string.auth_create_account),
            requirePasswordMatch = false,
            onSubmit = { _, _, _, _, _ -> },
        )
    }
}

@Preview(name = "Sign in · light", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun SignInHeroLight() {
    PreviewTheme(dark = false) { SignInPreviewBody() }
}

@Preview(name = "Sign in · dark", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun SignInHeroDark() {
    PreviewTheme(dark = true) { SignInPreviewBody() }
}

@Preview(name = "Sign in · split", widthDp = DESK_WIDTH, heightDp = DESK_HEIGHT)
@Composable
private fun SignInSplitLight() {
    PreviewTheme(dark = false) { SignInPreviewBody() }
}

@Preview(name = "Create admin · light", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun CreateAdminHeroLight() {
    PreviewTheme(dark = false) { CreateAdminPreviewBody() }
}

@Preview(name = "Create admin · dark", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun CreateAdminHeroDark() {
    PreviewTheme(dark = true) { CreateAdminPreviewBody() }
}

@Preview(name = "Create admin · split", widthDp = DESK_WIDTH, heightDp = DESK_HEIGHT)
@Composable
private fun CreateAdminSplitDark() {
    PreviewTheme(dark = true) { CreateAdminPreviewBody(onBack = {}) }
}

@Composable
private fun PendingApprovalPreviewBody(state: PendingApprovalUiState) {
    PendingApprovalContent(
        state = state,
        email = "newreader@example.com",
        onCheckStatus = {},
        onSignIn = {},
        onCancel = {},
    )
}

@Preview(name = "Pending approval · waiting · light", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun PendingApprovalWaitingLight() {
    PreviewTheme(dark = false) { PendingApprovalPreviewBody(PendingApprovalUiState.Waiting) }
}

@Preview(name = "Pending approval · waiting · dark", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun PendingApprovalWaitingDark() {
    PreviewTheme(dark = true) { PendingApprovalPreviewBody(PendingApprovalUiState.Waiting) }
}

@Preview(name = "Pending approval · approved · light", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun PendingApprovalApprovedLight() {
    PreviewTheme(dark = false) { PendingApprovalPreviewBody(PendingApprovalUiState.Approved) }
}

@Preview(name = "Pending approval · waiting · split", widthDp = DESK_WIDTH, heightDp = DESK_HEIGHT)
@Composable
private fun PendingApprovalWaitingSplit() {
    PreviewTheme(dark = false) { PendingApprovalPreviewBody(PendingApprovalUiState.Waiting) }
}
