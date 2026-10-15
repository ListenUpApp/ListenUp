import NukeUI
import SwiftUI
import Shared
import UIKit

/// Circular avatar for contributors (authors, narrators) with fallback chain.
///
/// Display priority:
/// 1. Local file image (if imagePath exists and is valid)
/// 2. Colored circle with initials derived from name
///
/// The fallback color is deterministically generated from the ID for consistency.
///
/// Usage:
/// ```swift
/// ContributorAvatar(
///     name: "Brandon Sanderson",
///     imagePath: contributor.imagePath,
///     id: contributorId
/// )
/// .frame(width: 48, height: 48)
/// ```
struct ContributorAvatar: View {
    let name: String
    let imagePath: String?
    let id: String
    /// How the photo sits in the circle; nil is today's centre crop.
    var frame: ImageFrameValue?

    /// Font size for initials (auto-calculated based on frame if not specified)
    var initialsFontSize: CGFloat = 16

    /// When true, `id` is a contributor id whose photo is streamed from the server (and lazily
    /// persisted to disk) via [ContributorPhotoLayer]. False for the generic uses of this avatar
    /// — admin user rows (where `id` is a *user* id) and the contributor-edit picked-file preview —
    /// which keep the local-file/initials behaviour.
    var streamsContributorPhoto: Bool = false

    /// Convenience initializer from a Contributor object — streams + persists the server photo.
    init(contributor: Contributor, fontSize: CGFloat = 16) {
        self.name = contributor.name
        self.imagePath = contributor.imagePath
        self.id = contributor.idString
        self.frame = ImageFrameValue(contributor.imageFrame)
        self.initialsFontSize = fontSize
        self.streamsContributorPhoto = true
    }

    /// Direct initializer for custom sources. Set `streamsContributorPhoto` when `id` is a
    /// contributor id whose server photo should render and be cached for offline use.
    init(
        name: String,
        imagePath: String?,
        id: String,
        fontSize: CGFloat = 16,
        streamsContributorPhoto: Bool = false,
        frame: ImageFrameValue? = nil
    ) {
        self.name = name
        self.imagePath = imagePath
        self.id = id
        self.frame = frame
        self.initialsFontSize = fontSize
        self.streamsContributorPhoto = streamsContributorPhoto
    }

    /// The on-disk avatar, read + decoded OFF the main thread by [loadImage]. Reading/decoding
    /// a file image synchronously in `body` (as this view used to) blocks the main thread on
    /// every render for every visible row, which freezes the app during a fast Contributors
    /// scroll — the same hazard that froze the cover grid. Until it loads, the initials
    /// fallback shows.
    @State private var fileImage: UIImage?

    var body: some View {
        ZStack {
            Circle()
                .fill(avatarColor)

            if let fileImage {
                FramedImage(image: fileImage, frame: frame)
                    .clipShape(Circle())
            } else {
                Text(initials)
                    // Initials are sized to the fixed avatar circle, not to the text setting.
                    .font(.system(size: initialsFontSize, weight: .semibold, design: .rounded)) // decorative fixed size
                    .foregroundStyle(AvatarPalette.initialsInk)
            }

            // Streamed contributor photo, layered over the initials placeholder. Nuke
            // owns the off-main decode + caching (scroll-safe, like the cover grid).
            if streamsContributorPhoto {
                ContributorPhotoLayer(contributorId: id, imagePath: imagePath, frame: frame)
                    .clipShape(Circle())
            }
        }
        .clipShape(Circle())
        // Re-reads only when the path or the zoom changes; cancelled when the row scrolls away. Skipped when
        // streaming — [ContributorPhotoLayer] owns the image, and `imagePath` is not a durable
        // on-disk path there.
        .task(id: FileImageKey(path: imagePath, zoom: frame?.zoom)) {
            guard !streamsContributorPhoto else { return }
            fileImage = await Self.loadImage(
                path: imagePath,
                maxPixelSize: Int(FramedImageGeometry.targetPixels(base: CGFloat(Self.maxAvatarPixels), frame: frame))
            )
        }
    }

    /// `@concurrent` ⇒ the disk read + image decode run on the cooperative pool, never the main
    /// actor, whatever the target's default for `nonisolated async` becomes (with approachable
    /// concurrency a plain `nonisolated async` runs on the caller's actor). Decodes a thumbnail, not
    /// the full-resolution photo. Returns `nil` for a missing path or unreadable file.
    @concurrent
    private nonisolated static func loadImage(path: String?, maxPixelSize: Int) async -> UIImage? {
        guard let path else { return nil }
        return ImageDownsampler.downsampledImage(atPath: path, maxPixelSize: maxPixelSize)
    }

    /// Identity for the file read: a new path, or a new zoom that needs a sharper decode.
    private struct FileImageKey: Equatable {
        let path: String?
        let zoom: Double?
    }

    /// The largest avatar drawn (the contributor hero, about 120pt) at 3x, rounded up.
    private nonisolated static let maxAvatarPixels = 400

    // MARK: - Private

    private var initials: String {
        let components = name.split(separator: " ")
        if components.count >= 2 {
            let first = components[0].prefix(1)
            let last = components[components.count - 1].prefix(1)
            return "\(first)\(last)".uppercased()
        } else if let first = components.first {
            return String(first.prefix(2)).uppercased()
        }
        return "?"
    }

    /// The contributor's `AvatarPalette` slot — stable across launches (the old `hashValue` hue
    /// changed every launch) and solved so the white initials read at 4.5:1.
    private var avatarColor: Color {
        AvatarPalette.fill(forKey: id)
    }
}

// MARK: - Contributor photo layer

/// Streams a contributor's photo (durable local file → authenticated server URL) via Nuke,
/// fading in over whatever placeholder [ContributorAvatar] draws beneath it. Building the request
/// also lazily persists the photo to disk for offline use. Transparent until the image resolves,
/// so the initials placeholder shows through.
private struct ContributorPhotoLayer: View {
    let contributorId: String
    /// The contributor's content-addressed image path. Folded into the cache key and the task id so
    /// a sync-driven photo change (new path) re-resolves and busts the cached image. On the
    /// local-file branch the disk copy is dropped upstream by `ContributorSyncDomainHandler` when
    /// `imagePath` changes, so the stale file is gone before this re-resolves.
    let imagePath: String?
    /// How the photo sits in the circle; a zoomed frame also asks for a sharper decode.
    let frame: ImageFrameValue?

    @Environment(\.displayScale) private var displayScale
    @State private var request: ImageRequest?
    /// The box's longest side in pixels, unzoomed.
    @State private var boxPixels: CGFloat = 0

    /// What to decode: the box, scaled up by the frame's zoom so a zoomed face stays sharp.
    private var targetMaxPixels: CGFloat { FramedImageGeometry.targetPixels(base: boxPixels, frame: frame) }

    var body: some View {
        LazyImage(request: request) { state in
            // Color.clear keeps this layer at its parent avatar's size even before an image
            // resolves. Without an always-present filler the empty content collapses to 0×0, so
            // onGeometryChange reports px=0, the `.task` guard (`targetMaxPixels > 0`) skips building
            // the request forever, and a re-scraped photo never (re)loads — the layer just keeps
            // showing whatever `request` held last (the stale photo). BookCoverImage never hit this
            // because its gradient placeholder always fills; this layer had no such base.
            ZStack {
                Color.clear
                if let uiImage = state.imageContainer?.image {
                    FramedImage(image: uiImage, frame: frame)
                        .transition(.opacity)
                }
            }
        }
        .onGeometryChange(for: CGSize.self) { proxy in proxy.size } action: { size in
            let pixels = (max(size.width, size.height) * displayScale).rounded()
            if pixels > 0, pixels != boxPixels {
                boxPixels = pixels
            }
        }
        // Cancelled when the row scrolls away; re-resolves when id/imagePath/size change.
        .task(id: TaskKey(contributorId: contributorId, imagePath: imagePath, targetPixels: targetMaxPixels)) {
            guard targetMaxPixels > 0 else { return }
            let built = await ContributorImageRequest.contributor(
                contributorId: contributorId,
                imagePath: imagePath,
                targetPixels: targetMaxPixels
            )
            // Propagate only on acceptance, never on cancellation: a superseded task resumes here
            // after its await and would clobber `request` with a stale (or tokenless→401) build. The
            // successor task rebuilds with the current inputs and a fresh token.
            guard !Task.isCancelled else { return }
            request = built
        }
    }

    /// Identity for the request-building task: any change re-resolves the photo source.
    private struct TaskKey: Equatable {
        let contributorId: String
        let imagePath: String?
        let targetPixels: CGFloat
    }
}

// MARK: - Preview

#Preview("With Initials") {
    HStack(spacing: 16) {
        ContributorAvatar(
            name: "Brandon Sanderson",
            imagePath: nil,
            id: "contributor-1"
        )
        .frame(width: 48, height: 48)

        ContributorAvatar(
            name: "Patrick Rothfuss",
            imagePath: nil,
            id: "contributor-2"
        )
        .frame(width: 48, height: 48)

        ContributorAvatar(
            name: "Tim Gerard Reynolds",
            imagePath: nil,
            id: "contributor-3"
        )
        .frame(width: 48, height: 48)
    }
    .padding()
}

#Preview("Large Avatar") {
    ContributorAvatar(
        name: "Brandon Sanderson",
        imagePath: nil,
        id: "contributor-1",
        fontSize: 40
    )
    .frame(width: 120, height: 120)
    .padding()
}
