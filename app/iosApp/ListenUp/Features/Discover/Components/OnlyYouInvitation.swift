import SwiftUI

/// Listening Now when you are the only person on the server (spec §3.8): your avatar beside two dashed
/// circles for the people not here yet, what Discover will show once they are, and — for an admin
/// only — **Invite Someone**. A member sees no button and no gap where it was.
///
/// A plain grouped section, not Android's tonal card (HIG): the system's secondary grouped fill.
struct OnlyYouInvitation: View {
    let viewer: DiscoverViewerRow?
    /// Presents the create-invite sheet; nil hides the button.
    let onInvite: (() -> Void)?

    private let avatarSize: CGFloat = 40

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(spacing: -10) {
                if let viewer {
                    UserAvatarView(userId: viewer.userId, fallbackName: viewer.displayName, size: avatarSize)
                }
                ForEach(0..<2, id: \.self) { _ in
                    Circle()
                        .strokeBorder(.secondary, style: StrokeStyle(lineWidth: 2, dash: [5, 4]))
                        .background(Circle().fill(Color(.tertiarySystemFill)))
                        .frame(width: avatarSize, height: avatarSize)
                }
            }
            .accessibilityHidden(true)

            Text(String(localized: "discover.only_you_title_ios"))
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Text(String(localized: "discover.only_you_body"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if let onInvite {
                Button(String(localized: "discover.invite_someone_ios"), action: onInvite)
                    .buttonStyle(.borderedProminent)
                    .tint(.listenUpOrange)
                    .buttonBorderShape(.capsule)
                    .frame(minHeight: 44)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.m)
        .background(
            Color(.secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
        )
    }
}
