import SwiftUI
@preconcurrency import Shared

/// Screen shown after registration when the user's account is awaiting admin approval.
///
/// Subscribes to the SSE registration-status stream via `PendingApprovalViewModel`.
/// Navigation is fully automatic — once `cancel()` or `acknowledge()` is called,
/// `clearPendingRegistration()` flips `AuthState` and `RootView` transitions away.
struct PendingApprovalView: View {

    // MARK: - State

    let userId: String
    let email: String
    @State private var wrapper: PendingApprovalViewModelWrapper

    // MARK: - Initialization

    init(userId: String, email: String) {
        self.userId = userId
        self.email = email
        _wrapper = State(initialValue: PendingApprovalViewModelWrapper(
            viewModel: Dependencies.shared.makePendingApprovalViewModel(userId: userId, email: email)
        ))
    }

    // MARK: - Body

    var body: some View {
        AuthScaffold {
            phaseContent
        } footer: {
            phaseFooter
        }
    }

    // MARK: - Phase content

    @ViewBuilder
    private var phaseContent: some View {
        switch wrapper.phase {
        case .waiting:
            waitingContent
        case .approved:
            approvedContent
        case .denied(let message):
            deniedContent(message: message)
        }
    }

    private var waitingContent: some View {
        VStack(alignment: .leading, spacing: 20) {
            AuthLargeHeader(
                title: String(localized: "setup.awaiting_approval_title"),
                subtitle: String(localized: "auth.pending_approval_message")
            ) {
                ProgressView()
                    .tint(Color.listenUpOrange)
            }
            Text(wrapper.email)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.listenUpOrange)
        }
    }

    private var approvedContent: some View {
        AuthLargeHeader(
            title: String(localized: "setup.approved_title"),
            subtitle: String(localized: "auth.sign_in_to_access_your")
        ) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 40))
                .foregroundStyle(Color.listenUpOrange)
                .accessibilityHidden(true)
        }
    }

    private func deniedContent(message: String) -> some View {
        AuthLargeHeader(
            title: String(localized: "auth.waiting_for_approval"),
            subtitle: message
        ) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 40))
                .foregroundStyle(.red)
                .accessibilityHidden(true)
        }
    }

    // MARK: - Phase footer

    @ViewBuilder
    private var phaseFooter: some View {
        switch wrapper.phase {
        case .waiting:
            Button(String(localized: "setup.cancel_registration")) {
                wrapper.cancel()
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
        case .approved:
            AuthPrimaryButton(title: String(localized: "auth.sign_in")) {
                wrapper.acknowledge()
            }
        case .denied:
            AuthPrimaryButton(title: String(localized: "setup.back_to_sign_in")) {
                wrapper.cancel()
            }
        }
    }
}
