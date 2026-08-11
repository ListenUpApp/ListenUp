import SwiftUI
import Shared

/// The forgot-password flow: request a reset, wait for an admin, then complete with the
/// out-of-band code the admin conveys.
///
/// Renders through the shared `AuthScaffold` like every other auth screen, one arm per
/// `ForgotPasswordPhase`. The manual **Check Status** button on the awaiting arm is the
/// never-stranded fallback beside the automatic stream/poll watch, exactly as
/// `PendingApprovalView` does for registration. The shared ViewModel resumes an in-flight
/// request across launches on its own — this view never has to.
struct ForgotPasswordView: View {

    // MARK: - Environment

    @Environment(\.navigateBack) private var navigateBack

    // MARK: - State

    @State private var observer = ForgotPasswordObserver(
        viewModel: Dependencies.shared.makeForgotPasswordViewModel()
    )
    @State private var email = ""
    @State private var code = ""
    @State private var newPassword = ""
    /// Locally hides retained wrong-code feedback once the user edits the code — the ViewModel
    /// deliberately keeps it (mirrors the Compose screen's `errorDismissed`). Re-arms on each
    /// phase change carrying fresh feedback because `onChange(of: observer.phase)` resets it.
    @State private var codeErrorDismissed = false

    // MARK: - Body

    var body: some View {
        AuthScaffold {
            phaseContent
        } footer: {
            phaseFooter
        }
        .onChange(of: observer.phase) { _, _ in codeErrorDismissed = false }
    }

    // MARK: - Phase content

    @ViewBuilder
    private var phaseContent: some View {
        switch observer.phase {
        case .enterEmail:
            VStack(alignment: .leading, spacing: 20) {
                AuthLargeHeader(
                    title: String(localized: "auth.forgot_password_title"),
                    subtitle: String(localized: "auth.forgot_password_explainer")
                )
                AuthFieldGroup {
                    AppTextField(
                        placeholder: String(localized: "common.email"),
                        text: $email,
                        icon: "envelope",
                        keyboardType: .emailAddress,
                        textContentType: .emailAddress
                    )
                }
            }
        case .submitting:
            VStack(spacing: 20) {
                AuthLargeHeader(title: String(localized: "auth.forgot_password_title"))
                ProgressView()
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
            }
        case .awaitingApproval:
            VStack(alignment: .leading, spacing: 20) {
                AuthLargeHeader(
                    title: String(localized: "auth.forgot_password_title"),
                    subtitle: String(localized: "auth.forgot_password_awaiting")
                ) {
                    Image(systemName: "clock")
                        .font(.system(size: 44))
                        .foregroundStyle(Color.listenUpOrange)
                        .accessibilityHidden(true)
                }
                autoCheckRow
            }
        case .enterCode(let attemptsRemaining, let error):
            enterCodeContent(attemptsRemaining: attemptsRemaining, error: error)
        case .denied:
            terminalContent(subtitle: String(localized: "auth.forgot_password_denied"), success: false)
        case .complete:
            terminalContent(subtitle: String(localized: "auth.forgot_password_complete"), success: true)
        case .error(let message):
            AuthLargeHeader(
                title: String(localized: "common.something_went_wrong"),
                subtitle: message
            ) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)
            }
        }
    }

    private func enterCodeContent(attemptsRemaining: Int?, error: String?) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            AuthLargeHeader(
                title: String(localized: "auth.forgot_password_title"),
                subtitle: String(localized: "auth.forgot_password_enter_code")
            )
            AuthFieldGroup {
                AppTextField(
                    placeholder: String(localized: "invite.enter_code"),
                    text: codeBinding,
                    icon: "key",
                    error: codeErrorDismissed ? nil : error,
                    isLast: false
                )
                AppTextField(
                    placeholder: String(localized: "auth.password_label"),
                    text: $newPassword,
                    kind: .secure,
                    textContentType: .newPassword
                )
            }
            if let attemptsRemaining {
                Text(String(format: String(localized: "auth.forgot_password_attempts"), attemptsRemaining))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.red)
            }
        }
    }

    private func terminalContent(subtitle: String, success: Bool) -> some View {
        AuthLargeHeader(
            title: String(localized: "auth.forgot_password_title"),
            subtitle: subtitle
        ) {
            Image(systemName: success ? "checkmark.circle.fill" : "xmark.circle.fill")
                .font(.system(size: 44))
                .foregroundStyle(success ? Color.listenUpOrange : .red)
                .accessibilityHidden(true)
        }
    }

    private var autoCheckRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "arrow.clockwise")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(String(localized: "auth.checking_automatically"))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Phase footer

    @ViewBuilder
    private var phaseFooter: some View {
        switch observer.phase {
        case .enterEmail:
            AuthPrimaryButton(title: String(localized: "common.continue")) {
                observer.requestReset(email: email.trimmingCharacters(in: .whitespaces))
            }
            .disabled(email.trimmingCharacters(in: .whitespaces).isEmpty)
        case .submitting:
            EmptyView()
        case .awaitingApproval:
            AuthPrimaryButton(title: String(localized: "auth.check_status")) {
                observer.checkStatus()
            }
        case .enterCode:
            AuthPrimaryButton(title: String(localized: "common.continue")) {
                observer.completeReset(code: code, newPassword: newPassword)
            }
            .disabled(code.isEmpty || newPassword.isEmpty)
        case .denied, .complete:
            AuthPrimaryButton(title: String(localized: "setup.back_to_sign_in")) {
                navigateBack()
            }
        case .error:
            AuthPrimaryButton(title: String(localized: "common.try_again")) {
                navigateBack()
            }
        }
    }

    /// Editing the code hides any retained wrong-code feedback until the next submit outcome.
    private var codeBinding: Binding<String> {
        Binding(
            get: { code },
            set: { newValue in
                code = newValue
                codeErrorDismissed = true
            }
        )
    }
}

#Preview("Forgot password") {
    NavigationStack { ForgotPasswordView() }
}
