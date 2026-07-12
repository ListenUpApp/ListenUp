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

    /// UI-local dismissal of the Offline banner; resets when the state next changes.
    @State private var offlineDismissed = false

    var body: some View {
        switch kind {
        case .unreachable:
            if !offlineDismissed {
                card(
                    icon: "wifi.slash",
                    title: Text("shell.offline_title"),
                    message: Text("shell.offline_body"),
                    actionLabel: Text("common.retry"),
                    onAction: onRetry,
                    onClose: { offlineDismissed = true }
                )
            }
        case .outdated(let clientVersion, let serverVersion):
            card(
                icon: "arrow.down.circle",
                title: Text("shell.update_available_title"),
                message: Text(updateBody(clientVersion, serverVersion)),
                actionLabel: Text("common.dismiss"),
                onAction: onDismiss,
                onClose: nil
            )
        case .hidden, .sessionExpired:
            EmptyView()
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
                actionLabel
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)

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
