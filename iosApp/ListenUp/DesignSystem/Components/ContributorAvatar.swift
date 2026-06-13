import SwiftUI
@preconcurrency import Shared
import UIKit

/// Circular avatar for contributors (authors, narrators) with fallback chain.
///
/// Display priority:
/// 1. Local file image (if imagePath exists and is valid)
/// 2. BlurHash placeholder (if blurHash exists)
/// 3. Colored circle with initials derived from name
///
/// The fallback color is deterministically generated from the ID for consistency.
///
/// Usage:
/// ```swift
/// ContributorAvatar(
///     name: "Brandon Sanderson",
///     imagePath: contributor.imagePath,
///     blurHash: contributor.imageBlurHash,
///     id: contributorId
/// )
/// .frame(width: 48, height: 48)
/// ```
struct ContributorAvatar: View {
    let name: String
    let imagePath: String?
    let blurHash: String?
    let id: String

    /// Font size for initials (auto-calculated based on frame if not specified)
    var initialsFontSize: CGFloat = 16

    /// Convenience initializer from a Contributor object
    init(contributor: Contributor, fontSize: CGFloat = 16) {
        self.name = contributor.name
        self.imagePath = contributor.imagePath
        self.blurHash = contributor.imageBlurHash
        self.id = contributor.idString
        self.initialsFontSize = fontSize
    }

    /// Direct initializer for custom sources
    init(name: String, imagePath: String?, blurHash: String? = nil, id: String, fontSize: CGFloat = 16) {
        self.name = name
        self.imagePath = imagePath
        self.blurHash = blurHash
        self.id = id
        self.initialsFontSize = fontSize
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(avatarColor)

            if let path = imagePath,
               let uiImage = UIImage(contentsOfFile: path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .clipShape(Circle())
            } else if let blurHash {
                BlurHashView(blurHash: blurHash)
                    .clipShape(Circle())
            } else {
                Text(initials)
                    .font(.system(size: initialsFontSize, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            }
        }
        .clipShape(Circle())
    }

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

    private var avatarColor: Color {
        let hash = id.hashValue
        let hue = Double(abs(hash) % 360) / 360.0
        return Color(hue: hue, saturation: 0.5, brightness: 0.7)
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
