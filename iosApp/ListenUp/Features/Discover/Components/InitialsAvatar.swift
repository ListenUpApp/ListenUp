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
    /// Optional explicit fill (the activity feed passes the user's stored avatar color).
    var tint: Color?

    var body: some View {
        Circle()
            .fill(background)
            .frame(width: size, height: size)
            .overlay {
                Text(initials)
                    .font(.system(size: size * 0.36, weight: .semibold))
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
        return tint == nil ? Color.luLabel2 : .white
    }
}

#Preview {
    HStack(spacing: 16) {
        InitialsAvatar(initials: "ML")
        InitialsAvatar(initials: "SH", isCurrentUser: true)
        InitialsAvatar(initials: "PN", tint: .blue)
    }
    .padding()
}
