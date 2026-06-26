import SwiftUI
import Shared
import UIKit

/// Circular avatar displaying user initials or image.
///
/// Shows:
/// - User's uploaded avatar image, downloaded + persisted to disk on first appearance
/// - Colored circle with initials as fallback
/// - Gray placeholder when user is nil
struct UserAvatarView: View {
    private let userId: String?
    private let fallbackName: String
    private let avatarColorHex: String?
    private let size: CGFloat

    /// Render from a known Kotlin `User`. Used by toolbars / profile screens.
    init(user: User?, size: CGFloat = 36) {
        self.userId = user?.idString
        self.fallbackName = user?.displayName ?? ""
        self.avatarColorHex = user?.avatarColor
        self.size = size
    }

    /// Render from primitives — for list rows that carry only the user's id (per iOS rule 8,
    /// we never put a bridged Kotlin `User` in a `ForEach`). Resolves the picture by `userId`,
    /// falling back to initials derived from `fallbackName`.
    init(userId: String, fallbackName: String, avatarColor: String? = nil, size: CGFloat = 36) {
        self.userId = userId
        self.fallbackName = fallbackName
        self.avatarColorHex = avatarColor
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
            } else if userId != nil {
                initialsAvatar
            } else {
                placeholderAvatar
            }
        }
        .task(id: userId) {
            guard let userId else {
                avatarImage = nil
                return
            }
            avatarImage = await Self.loadAvatar(userId: userId)
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

    private var initialsAvatar: some View {
        Circle()
            .fill(initialsColor)
            .frame(width: size, height: size)
            .overlay {
                Text(Self.initials(from: fallbackName))
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

    private var initialsColor: Color {
        if let avatarColorHex { return Color(hex: avatarColorHex) }
        return Color.luFill
    }

    /// Up to two uppercased initials from a display name; "?" when blank.
    static func initials(from name: String) -> String {
        let words = name.split(separator: " ").prefix(2)
        let letters = words.compactMap { $0.first }.map(String.init).joined().uppercased()
        return letters.isEmpty ? "?" : letters
    }
}

// MARK: - Preview

#Preview("With User") {
    UserAvatarView(userId: "u1", fallbackName: "Ada Lovelace")
}

#Preview("Placeholder") {
    UserAvatarView(user: nil, size: 40)
}
