import SwiftUI

/// Non-blocking connection-health banner for the mounted shell: "Offline" (server unreachable,
/// auto-reconnecting, with a manual Retry) and "Update available" (a meaningful client/server
/// version skew, with Dismiss). Never modal — browsing and downloaded playback stay fully usable
/// underneath (Never Stranded). A Liquid-Glass card matching `SessionLapsedBanner`.
///
/// `sessionExpired` is intentionally NOT rendered here — it has its own `SessionLapsedBanner` driven
/// by `AuthStateObserver`, shown in the app root's `.sessionLapsed` branch.
struct ConnectionHealthBanner: View {
    let kind: ConnectionHealthKind
    var onRetry: () -> Void
    var onDismiss: () -> Void

    /// UI-local dismissal of the Offline banner; re-armed when the connection state next changes.
    @State private var offlineDismissed = false
    /// True for a short beat after Retry is tapped — swaps the action for a spinner so the tap has
    /// visible feedback even when the recheck leaves the server still unreachable.
    @State private var isRetrying = false

    var body: some View {
        Group {
            switch kind {
            case .unreachable:
                if !offlineDismissed {
                    card(
                        icon: "wifi.slash",
                        title: Text("shell.offline_title"),
                        message: Text("shell.offline_body"),
                        actionLabel: Text("common.retry"),
                        actionInProgress: isRetrying,
                        onAction: { isRetrying = true; onRetry() },
                        onClose: { offlineDismissed = true }
                    )
                }
            case .outdated(let clientVersion, let serverVersion):
                card(
                    icon: "arrow.down.circle",
                    title: Text("shell.update_available_title"),
                    message: Text(updateBody(clientVersion, serverVersion)),
                    actionLabel: Text("common.dismiss"),
                    actionInProgress: false,
                    onAction: onDismiss,
                    onClose: nil
                )
            case .hidden, .sessionExpired:
                EmptyView()
            }
        }
        .onChange(of: kind) { _, _ in
            // A genuine connection-state change resolves any pending Retry and re-arms a prior
            // offline dismissal, so the next real offline event surfaces the banner again.
            isRetrying = false
            offlineDismissed = false
        }
        .task(id: isRetrying) {
            // Hold the spinner for a visible beat even when the server stays down (kind unchanged),
            // then fall back to the Retry affordance so the user can try again.
            guard isRetrying else { return }
            try? await Task.sleep(for: .seconds(1.5))
            isRetrying = false
        }
    }

    private func updateBody(_ client: String, _ server: String) -> String {
        String(format: String(localized: "shell.update_available_body"), client, server)
    }

    @ViewBuilder
    private func card(
        icon: String,
        title: Text,
        message: Text,
        actionLabel: Text,
        actionInProgress: Bool,
        onAction: @escaping () -> Void,
        onClose: (() -> Void)?
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(Color.listenUpOrange)

            VStack(alignment: .leading, spacing: 2) {
                title
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                message
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            Button(action: onAction) {
                Group {
                    if actionInProgress {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        actionLabel
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.listenUpOrange)
                    }
                }
                .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .disabled(actionInProgress)

            if let onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "common.dismiss"))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassControl(in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 16)
    }
}
