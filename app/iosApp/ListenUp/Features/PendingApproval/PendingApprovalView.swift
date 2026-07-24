import SwiftUI
import Shared

/// Screen shown after registration when the user's account is awaiting admin approval.
///
/// Renders through the shared `AuthScaffold` so it matches the rest of the auth flow. The body
/// shows the registration as a three-step timeline, a manual **Check Status** re-check alongside
/// the always-on SSE stream (never stranded), and a Cancel action. Cancelling / acknowledging /
/// being denied all clear the pending registration, which flips `AuthState` back to login and
/// `RootView` transitions away automatically.
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
                PendingSpinner()
            }
            PendingReviewChip()
            RegistrationTimeline(email: wrapper.email)
            AutoCheckRow()
        }
    }

    private var approvedContent: some View {
        AuthLargeHeader(
            title: String(localized: "setup.approved_title"),
            subtitle: String(localized: "auth.sign_in_to_access_your")
        ) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 44))
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
                .font(.system(size: 44))
                .foregroundStyle(.red)
                .accessibilityHidden(true)
        }
    }

    // MARK: - Phase footer

    @ViewBuilder
    private var phaseFooter: some View {
        switch wrapper.phase {
        case .waiting:
            VStack(spacing: 12) {
                AuthPrimaryButton(title: String(localized: "auth.check_status")) {
                    wrapper.checkStatus()
                }
                Button(String(localized: "setup.cancel_registration")) {
                    wrapper.cancel()
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.red)
            }
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

// MARK: - Pending spinner (indeterminate clock)

private struct PendingSpinner: View {
    @State private var spin = false

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.listenUpOrange.opacity(0.12))
            Circle()
                .trim(from: 0, to: 0.3)
                .stroke(Color.listenUpOrange, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .rotationEffect(.degrees(spin ? 360 : 0))
            Image(systemName: "clock")
                .font(.system(size: 24, weight: .regular))
                .foregroundStyle(Color.listenUpOrange)
        }
        .frame(width: 64, height: 64)
        .onAppear {
            withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) { spin = true }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Pending review chip

private struct PendingReviewChip: View {
    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(Color.listenUpOrange)
                .frame(width: 7, height: 7)
            Text(String(localized: "auth.pending_review"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.listenUpOrange)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(Capsule().fill(Color.listenUpOrange.opacity(0.13)))
    }
}

// MARK: - Registration timeline

private struct RegistrationTimeline: View {
    let email: String

    var body: some View {
        AuthFieldGroup {
            RegStepRow(
                state: .done,
                icon: "person",
                title: String(localized: "auth.reg_step_account_created"),
                subtitle: email
            )
            Divider().padding(.leading, 60)
            RegStepRow(
                state: .active,
                icon: "shield",
                title: String(localized: "auth.reg_step_admin_approval"),
                subtitle: String(localized: "auth.reg_step_admin_approval_sub")
            )
            Divider().padding(.leading, 60)
            RegStepRow(
                state: .todo,
                icon: "headphones",
                title: String(localized: "auth.reg_step_start_listening"),
                subtitle: String(localized: "auth.reg_step_start_listening_sub")
            )
        }
    }
}

private enum StepState {
    case done, active, todo
}

private struct RegStepRow: View {
    let state: StepState
    let icon: String
    let title: String
    let subtitle: String

    private var circleColor: Color {
        switch state {
        case .done: return Color.listenUpOrange
        case .active: return Color.listenUpOrange.opacity(0.16)
        case .todo: return Color(.tertiarySystemFill)
        }
    }

    private var iconColor: Color {
        switch state {
        case .done: return .white
        case .active: return Color.listenUpOrange
        case .todo: return .secondary
        }
    }

    var body: some View {
        HStack(spacing: 13) {
            ZStack {
                Circle().fill(circleColor)
                Image(systemName: state == .done ? "checkmark" : icon)
                    .font(.system(size: 15, weight: state == .done ? .bold : .regular))
                    .foregroundStyle(iconColor)
            }
            .frame(width: 32, height: 32)

            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    if state == .active {
                        Text(String(localized: "auth.reg_step_in_progress").uppercased())
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(Color.listenUpOrange)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.listenUpOrange.opacity(0.15)))
                    }
                }
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

// MARK: - Auto-check status line

private struct AutoCheckRow: View {
    var body: some View {
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
}
