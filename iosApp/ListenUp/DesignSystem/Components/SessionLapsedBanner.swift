import SwiftUI

/// Non-blocking "Signed out — sign in to sync" banner shown while the session is lapsed.
/// Never modal: browsing and downloaded playback stay fully usable underneath (M2/M3). The only
/// navigation is the user tapping Sign in; it is dismissable for the session (Never Stranded — the
/// app is never walled). A Liquid-Glass card matching `ServerUnreachableBanner`.
struct SessionLapsedBanner: View {
    var onSignIn: () -> Void
    @State private var dismissed = false

    var body: some View {
        if !dismissed {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle.badge.exclamationmark")
                    .font(.title3)
                    .foregroundStyle(Color.listenUpOrange)

                VStack(alignment: .leading, spacing: 2) {
                    Text("shell.session_lapsed_title")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text("shell.session_lapsed_body")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 8)

                Button(action: onSignIn) {
                    Text("shell.session_lapsed_sign_in")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.listenUpOrange)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "shell.session_lapsed_sign_in"))

                Button {
                    dismissed = true
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "common.dismiss"))
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassControl(in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .padding(.horizontal, 16)
        }
    }
}
