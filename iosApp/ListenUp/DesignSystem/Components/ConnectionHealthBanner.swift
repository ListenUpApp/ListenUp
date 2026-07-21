import SwiftUI

/// Non-blocking connection-health banner for the mounted shell: "Update available" (a meaningful
/// client/server version skew, with Dismiss). Never modal — browsing and playback stay fully usable
/// underneath (Never Stranded). A Liquid-Glass card matching `SessionLapsedBanner`.
///
/// `sessionExpired` is intentionally NOT rendered here — it has its own `SessionLapsedBanner` driven
/// by `AuthStateObserver`, shown in the app root's `.sessionLapsed` branch. There is deliberately no
/// ambient "offline" state either: ListenUp is offline-first, and connectivity is surfaced only at
/// point of need (book detail, player).
struct ConnectionHealthBanner: View {
    let kind: ConnectionHealthKind
    var onDismiss: () -> Void

    var body: some View {
        switch kind {
        case .outdated(let clientVersion, let serverVersion):
            card(
                icon: "arrow.down.circle",
                title: Text("shell.update_available_title"),
                message: Text(updateBody(clientVersion, serverVersion)),
                actionLabel: Text("common.dismiss"),
                onAction: onDismiss
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
        onAction: @escaping () -> Void
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
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassControl(in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 16)
    }
}
