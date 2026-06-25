import SwiftUI
@preconcurrency import Shared
import UIKit

/// Circular avatar displaying user initials or image.
///
/// Shows:
/// - User's uploaded avatar image, downloaded + persisted to disk on first appearance
/// - Colored circle with initials as fallback
/// - Gray placeholder when user is nil
struct UserAvatarView: View {
    let user: User?
    let size: CGFloat

    init(user: User?, size: CGFloat = 36) {
        self.user = user
        self.size = size
    }

    /// The user's image avatar, read off the main thread by [loadAvatar] — downloading + persisting
    /// it on first appearance so it renders now and survives offline. Until it lands (or for an
    /// auto/initials avatar), the initials fallback shows.
    @State private var avatarImage: UIImage?

    var body: some View {
        Group {
            if let avatarImage {
                Image(uiImage: avatarImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else if let user {
                initialsAvatar(for: user)
            } else {
                placeholderAvatar
            }
        }
        .task(id: user?.idString) {
            // Read the Sendable primitives on the main actor; only the String id crosses into the
            // nonisolated loader (the Kotlin `User` isn't Sendable).
            guard let user, user.hasImageAvatar else {
                avatarImage = nil
                return
            }
            avatarImage = await Self.loadAvatar(userId: user.idString)
        }
    }

    /// `nonisolated async` ⇒ the download, disk read, and decode run off the main actor. Persists
    /// the image avatar to the durable store on first appearance (no-op once cached) and loads it;
    /// returns nil if no avatar is available. `downloadUserAvatar` saves on success.
    private nonisolated static func loadAvatar(userId: String) async -> UIImage? {
        let repository = Dependencies.shared.imageRepository
        if !repository.userAvatarExists(userId: userId) {
            _ = try? await repository.downloadUserAvatar(userId: userId, forceRefresh: false)
        }
        guard repository.userAvatarExists(userId: userId) else { return nil }
        return UIImage(contentsOfFile: repository.getUserAvatarPath(userId: userId))
    }

    // MARK: - Private Views

    private func initialsAvatar(for user: User) -> some View {
        Circle()
            .fill(avatarColor(for: user))
            .frame(width: size, height: size)
            .overlay {
                Text(user.initials)
                    .font(.system(size: size * 0.4, weight: .medium))
                    .foregroundStyle(.white)
            }
    }

    private var placeholderAvatar: some View {
        Circle()
            .fill(Color.gray.opacity(0.3))
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: "person.fill")
                    .font(.system(size: size * 0.5))
                    .foregroundStyle(.secondary)
            }
    }

    private func avatarColor(for user: User) -> Color {
        Color(hex: user.avatarColor)
    }
}

// MARK: - Preview

#Preview("With User") {
    // Can't easily create a User in preview without Kotlin, so show placeholder
    UserAvatarView(user: nil, size: 40)
}

#Preview("Placeholder") {
    UserAvatarView(user: nil, size: 40)
}
