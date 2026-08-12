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
                ForgotPasswordHowItWorks(steps: [
                    String(localized: "auth.forgot_password_step_request"),
                    String(localized: "auth.forgot_password_step_code"),
                    String(localized: "auth.forgot_password_step_finish"),
                ])
            }
        case .submitting:
            VStack(spacing: 20) {
                AuthLargeHeader(title: String(localized: "auth.forgot_password_title"))
                ProgressView()
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
            }
        case .awaitingApproval(let ticketId):
            awaitingContent(ticketId: ticketId)
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
        let displayedError = codeErrorDismissed ? nil : error
        return VStack(alignment: .leading, spacing: 20) {
            AuthLargeHeader(
                title: String(localized: "auth.forgot_password_title"),
                subtitle: String(localized: "auth.forgot_password_enter_code")
            )
            ForgotPasswordCodeField(code: codeBinding, isError: displayedError != nil)
            if let displayedError {
                Text(displayedError)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity)
            }
            AuthFieldGroup {
                AppTextField(
                    placeholder: String(localized: "auth.password_label"),
                    text: $newPassword,
                    kind: .secure,
                    textContentType: .newPassword
                )
            }
            Text(String(localized: "auth.forgot_password_reveal_hint"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            ForgotPasswordAttempts(remaining: attemptsRemaining)
        }
    }

    private func terminalContent(subtitle: String, success: Bool) -> some View {
        AuthLargeHeader(
            title: String(localized: "auth.forgot_password_title"),
            subtitle: subtitle
        ) {
            ForgotPasswordMark(symbol: success ? "checkmark" : "xmark", tone: success ? .good : .bad)
        }
    }

    /// Waiting for a person. No admin is named — several may hold the role, and this is the one
    /// state a request for an unrecognised address also reaches, so a name here would tell a
    /// stranger whether an account exists.
    private func awaitingContent(ticketId: String) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            AuthLargeHeader(
                title: String(localized: "auth.forgot_password_title"),
                subtitle: String(localized: "auth.forgot_password_awaiting")
            ) {
                ForgotPasswordMark(symbol: "clock")
            }
            // The raw ticket id is `<uuid>.<issuedAtMs>.<sig>` — an internal blob. Show only the
            // first UUID segment as the human-scale reference; enough to quote to an admin.
            Text(String(format: String(localized: "auth.forgot_password_ticket"), ticketReference(ticketId)))
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .padding(.horizontal, 13)
                .padding(.vertical, 6)
                .background(.background.secondary, in: .capsule)
                .frame(maxWidth: .infinity)

            ForgotPasswordTimeline(activeStep: 1)

            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Image(systemName: "info.circle")
                    .font(.footnote)
                Text(String(localized: "auth.forgot_password_survives"))
                    .font(.footnote)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .foregroundStyle(.secondary)

            autoCheckRow
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
            AuthPrimaryButton(title: String(localized: "auth.forgot_password_send_request")) {
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
        case .denied:
            VStack(spacing: 12) {
                AuthPrimaryButton(title: String(localized: "auth.forgot_password_retry")) {
                    observer.retryRequest()
                }
                Button(String(localized: "setup.back_to_sign_in")) { navigateBack() }
                    .font(.subheadline)
            }
        case .complete:
            AuthPrimaryButton(title: String(localized: "setup.back_to_sign_in")) {
                navigateBack()
            }
        case .error:
            AuthPrimaryButton(title: String(localized: "common.try_again")) {
                navigateBack()
            }
        }
    }

    /// The short, quotable form of the signed ticket id — matching what the Compose screen shows.
    private func ticketReference(_ ticketId: String) -> String {
        String(ticketId.prefix(while: { $0 != "." && $0 != "-" })).uppercased()
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
