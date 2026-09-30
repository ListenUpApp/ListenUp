import SwiftUI

/// A neutral initials avatar for the Discover leaderboard and activity feed.
///
/// Distinct from `UserAvatarView` (which renders a *known* `User` with their stored
/// avatar color): these surfaces have only a display name's initials and no `User`, so
/// the avatar is intentionally neutral — a system fill with secondary-label initials. The
/// current user's leaderboard row is tinted coral via `isCurrentUser`, matching the design.
struct InitialsAvatar: View {
    let initials: String
    var size: CGFloat = 38
    var isCurrentUser = false
    /// Optional explicit fill — an `AvatarPalette` colour, which the white initials are solved against.
    var tint: Color?

    var body: some View {
        Circle()
            .fill(background)
            .frame(width: size, height: size)
            .overlay {
                Text(initials)
                    // Initials are sized to the fixed avatar circle, not to the text setting.
                    .font(.system(size: size * 0.36, weight: .semibold)) // decorative fixed size
                    .foregroundStyle(foreground)
            }
    }

    private var background: Color {
        if isCurrentUser {
            return Color.luTint.opacity(0.15)
        }
        return tint ?? Color.luFill
    }

    private var foreground: Color {
        if isCurrentUser {
            return Color.luTint
        }
        return tint == nil ? Color.secondary : AvatarPalette.initialsInk
    }
}

#Preview {
    HStack(spacing: 16) {
        InitialsAvatar(initials: "ML")
        InitialsAvatar(initials: "SH", isCurrentUser: true)
        InitialsAvatar(initials: "PN", tint: AvatarPalette.fill(forKey: "PN"))
    }
    .padding()
}
