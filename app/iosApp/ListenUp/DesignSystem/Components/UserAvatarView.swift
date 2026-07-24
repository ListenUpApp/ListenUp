import NukeUI
import SwiftUI
import Shared

/// Circular avatar displaying a user's image or their initials.
///
/// The avatar resolves reactively from the ONE source of avatar truth — the user's
/// `public_profiles` row (`UserProfileRepository.observeProfile`). The observed row carries
/// the avatar `type` (image vs auto/initials) and an `updatedAt` version that bumps on every
/// avatar change, so a re-uploaded avatar re-renders instead of showing the stale bitmap.
///
/// Shows:
/// - The user's uploaded image, streamed content-addressed via Nuke (`UserAvatarPhotoLayer`) and
///   lazily persisted to disk for offline use.
/// - A colored circle with initials as fallback (auto avatar, or image not yet resolved).
/// - A gray placeholder when no user is known.
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

    var body: some View {
        ZStack {
            if userId != nil {
                initialsAvatar
            } else {
                placeholderAvatar
            }

            // Streamed, content-addressed avatar layered over the initials placeholder — only for an
            // image-type avatar. Nuke owns the off-main decode + caching; the version folded into the
            // request identity busts the cache on a re-upload, so it never shows the stale bitmap.
            if let userId, profile.avatarType == Self.imageAvatarType {
                UserAvatarPhotoLayer(userId: userId, version: profile.version)
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            }
        }
        .frame(width: size, height: size)
        .task(id: userId) { profile.observe(userId: userId) }
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

// MARK: - Avatar photo layer

/// Streams a user's avatar (content-addressed authenticated server URL → durable local file) via
/// Nuke, fading in over whatever placeholder `UserAvatarView` draws beneath it. Transparent until
/// the image resolves, so the initials placeholder shows through.
///
/// `Color.clear` keeps the layer at its parent avatar's size even before an image resolves; without
/// an always-present filler the empty content collapses to 0×0, `onGeometryChange` reports px=0, the
/// `.task` guard skips building the request forever, and the avatar never (re)loads (the same trap
/// `ContributorPhotoLayer` hit).
private struct UserAvatarPhotoLayer: View {
    let userId: String
    /// The avatar content version (the profile row's `updatedAt`). Folded into the cache key and the
    /// task id so a re-uploaded avatar (new version) re-resolves and busts the cached image.
    let version: Int64

    @Environment(\.displayScale) private var displayScale
    @State private var request: ImageRequest?
    @State private var targetMaxPixels: CGFloat = 0

    var body: some View {
        LazyImage(request: request) { state in
            ZStack {
                Color.clear
                if let image = state.image {
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .transition(.opacity)
                }
            }
        }
        .onGeometryChange(for: CGSize.self) { proxy in proxy.size } action: { size in
            let pixels = (max(size.width, size.height) * displayScale).rounded()
            if pixels > 0, pixels != targetMaxPixels {
                targetMaxPixels = pixels
            }
        }
        // Cancelled when the row scrolls away; re-resolves when id/version/size change.
        .task(id: TaskKey(userId: userId, version: version, targetPixels: targetMaxPixels)) {
            guard targetMaxPixels > 0 else { return }
            let built = await UserAvatarImageRequest.avatar(
                userId: userId,
                version: version,
                targetPixels: targetMaxPixels
            )
            // Propagate only on acceptance, never on cancellation: a superseded task resumes here and
            // would clobber `request` with a stale (or tokenless→401) build.
            guard !Task.isCancelled else { return }
            request = built
        }
    }

    /// Identity for the request-building task: any change re-resolves the avatar source.
    private struct TaskKey: Equatable {
        let userId: String
        let version: Int64
        let targetPixels: CGFloat
    }
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
