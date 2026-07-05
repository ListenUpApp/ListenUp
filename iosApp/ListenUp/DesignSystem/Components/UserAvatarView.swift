import SwiftUI
import Shared
import UIKit

/// Circular avatar displaying a user's image or their initials.
///
/// The avatar resolves reactively from the ONE source of avatar truth — the user's
/// `public_profiles` row (`UserProfileRepository.observeProfile`). The observed row carries
/// the avatar `type` (image vs auto/initials) and an `updatedAt` version that bumps on every
/// avatar change, so a re-uploaded avatar re-renders instead of showing the stale bitmap.
///
/// Shows:
/// - The user's uploaded image, read from disk (downloaded + persisted on first appearance)
/// - A colored circle with initials as fallback (auto avatar, or image not yet cached)
/// - A gray placeholder when no user is known
struct UserAvatarView: View {
    private let userId: String?
    private let fallbackName: String
    private let size: CGFloat

    /// Render from a known Kotlin `User`. Used by toolbars / profile screens.
    init(user: User?, size: CGFloat = 36) {
        self.userId = user?.idString
        self.fallbackName = user?.displayName ?? ""
        self.size = size
    }

    /// Render from primitives — for list rows that carry only the user's id (per iOS rule 8,
    /// we never put a bridged Kotlin `User` in a `ForEach`). The picture and avatar type are
    /// resolved by observing `userId`'s profile; `fallbackName` seeds the initials.
    init(userId: String, fallbackName: String, size: CGFloat = 36) {
        self.userId = userId
        self.fallbackName = fallbackName
        self.size = size
    }

    /// Observes the user's `public_profiles` row for avatar type + version. Rebound whenever
    /// `userId` changes; its published `avatarType`/`version` drive the gate and the reload key.
    @State private var profile = AvatarProfileObserver()

    /// The user's image avatar, read off the main thread by [loadAvatar] — fire-and-forget caching
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
        .task(id: userId) { profile.observe(userId: userId) }
        // Reload keyed on (userId, avatarType, version): a changed avatar bumps `version`, so the
        // new bytes are re-read from disk rather than the view showing the stale cached bitmap.
        .task(id: reloadKey) {
            guard let userId, profile.avatarType == Self.imageAvatarType else {
                avatarImage = nil
                return
            }
            avatarImage = await Self.loadAvatar(userId: userId)
        }
    }

    /// Changes whenever the identity or the observed avatar version changes, re-triggering the
    /// image reload. `version` is the observed row's `updatedAt` (the avatar content version).
    private var reloadKey: String {
        "\(userId ?? "")|\(profile.avatarType ?? "")|\(profile.version)"
    }

    /// `nonisolated async` ⇒ disk read + decode run off the main actor. Does NOT await the bridged
    /// `downloadUserAvatar` suspend (that AppResult suspend-bridge deadlocks under the post-scan
    /// concurrent fan-out). Instead fire-and-forgets `ensureUserAvatarCached` (the cover/contributor/
    /// Compose pattern) and briefly polls for the downloaded file to land.
    private nonisolated static func loadAvatar(userId: String) async -> UIImage? {
        let repository = Dependencies.shared.imageRepository
        if repository.userAvatarExists(userId: userId) {
            return UIImage(contentsOfFile: repository.getUserAvatarPath(userId: userId))
        }
        repository.ensureUserAvatarCached(userId: userId)
        for _ in 0..<8 {
            try? await Task.sleep(for: .milliseconds(250))
            if Task.isCancelled { return nil }
            if repository.userAvatarExists(userId: userId) {
                return UIImage(contentsOfFile: repository.getUserAvatarPath(userId: userId))
            }
        }
        return nil
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

    /// Deterministic per-user color for the initials fallback, derived from the id alone — the
    /// avatar's color no longer travels as a field; it's a stable function of who the user is.
    private var initialsColor: Color {
        guard let userId else { return Color.luFill }
        return avatarColorForUserId(userId)
    }

    /// Up to two uppercased initials from a display name; "?" when blank.
    static func initials(from name: String) -> String {
        let words = name.split(separator: " ").prefix(2)
        let letters = words.compactMap { $0.first }.map(String.init).joined().uppercased()
        return letters.isEmpty ? "?" : letters
    }

    /// The `public_profiles` avatar-type value that means "show the uploaded image".
    private static let imageAvatarType = "image"
}

// MARK: - Profile observation

/// Observes a single user's `public_profiles` row — the ONE avatar source — publishing the
/// avatar `type` and its `version` so a `UserAvatarView` re-renders when the avatar changes,
/// not merely when the user id does (the #11 fix).
@Observable
@MainActor
final class AvatarProfileObserver {
    private(set) var avatarType: String?
    /// The observed row's `updatedAt`, doubling as the avatar's content version.
    private(set) var version: Int64 = 0

    private let repository: UserProfileRepository = Dependencies.shared.userProfileRepository
    private let bridge = FlowBridge()
    private var observedUserId: String?

    /// (Re)bind to `userId`'s profile. A no-op when already observing that id, so it's safe to
    /// drive from `.task(id:)` on every appearance.
    func observe(userId: String?) {
        guard userId != observedUserId else { return }
        observedUserId = userId
        bridge.cancelAll()
        avatarType = nil
        version = 0
        guard let userId else { return }
        bridge.bind(repository.observeProfile(userId: userId)) { [weak self] profile in
            self?.avatarType = profile?.avatarType
            self?.version = profile?.updatedAt ?? 0
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.
}

// MARK: - Preview

#Preview("With User") {
    UserAvatarView(userId: "u1", fallbackName: "Ada Lovelace")
}

#Preview("Placeholder") {
    UserAvatarView(user: nil, size: 40)
}
