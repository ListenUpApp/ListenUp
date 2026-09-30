import SwiftUI
import UIKit

/// Explains that Local Network access is off for ListenUp and links to the one place it can be
/// turned back on.
///
/// Settings, not a re-prompt: iOS shows the Local Network prompt once per install and has no API to
/// ask again, and the HIG (Privacy) points people to Settings to "update their choice". The denial
/// itself is detected as Apple TN3179 describes — Bonjour's `PolicyDenied`, or a probe connection
/// waiting with `local_network_denied`.
struct LocalNetworkNotice: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label {
                Text(String(localized: "error.server_connect_local_network_permission_denied"))
                    .font(.subheadline)
            } icon: {
                Image(systemName: "wifi.exclamationmark")
                    .foregroundStyle(Color.listenUpOrange)
            }
            Text(String(localized: "connect.local_network_hint_ios"))
                .font(.footnote).foregroundStyle(.secondary)
            Button(String(localized: "connect.local_network_open_settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(.secondarySystemGroupedBackground))
        )
        .accessibilityElement(children: .contain)
    }
}

#Preview("Local network notice") {
    LocalNetworkNotice().padding()
}
