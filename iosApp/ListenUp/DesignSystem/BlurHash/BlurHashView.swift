import SwiftUI
import UIKit

/// SwiftUI view that displays a decoded BlurHash placeholder.
///
/// Usage:
/// ```swift
/// BlurHashView(blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj", size: CGSize(width: 32, height: 32))
///     .frame(width: 200, height: 300)
/// ```
///
/// The `size` parameter controls the decode resolution (not display size).
/// Smaller sizes decode faster; 32x32 is typically sufficient for placeholders.
struct BlurHashView: View {
    let blurHash: String?
    let size: CGSize
    let punch: Float

    /// Creates a BlurHash view.
    ///
    /// - Parameters:
    ///   - blurHash: The BlurHash string to decode, or nil for fallback
    ///   - size: Decode resolution (default 32x32, sufficient for blur effect)
    ///   - punch: Contrast adjustment (default 1.0, higher = more vibrant)
    init(
        blurHash: String?,
        size: CGSize = CGSize(width: 32, height: 32),
        punch: Float = 1.0
    ) {
        self.blurHash = blurHash
        self.size = size
        self.punch = punch
    }

    /// Decoded blur, produced OFF the main thread by [decode]. Decoding a BlurHash is a
    /// CPU-heavy per-pixel cosine loop; doing it synchronously in `body` (as this view used
    /// to) re-ran on every render for every visible cell, so a fast list/grid scroll — e.g.
    /// dragging the alphabet scrubber over a large library — saturated the main thread and
    /// froze the app.
    @State private var decoded: UIImage?

    var body: some View {
        Group {
            if let decoded {
                Image(uiImage: decoded)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                // Shown for a missing/invalid BlurHash and until the off-main decode lands.
                LinearGradient(
                    colors: [Color.gray.opacity(0.3), Color.gray.opacity(0.2)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            }
        }
        // Re-decodes only when the hash/size/punch actually change, and is cancelled when the
        // cell scrolls away — so a scrub never piles synchronous decodes onto the main thread.
        .task(id: decodeKey) {
            decoded = await Self.decode(blurHash: blurHash, size: size, punch: punch)
        }
    }

    /// Identity for [body]'s `.task`: the inputs that determine the decoded image.
    private var decodeKey: String { "\(blurHash ?? "")|\(Int(size.width))x\(Int(size.height))|\(punch)" }

    /// `nonisolated async` ⇒ runs on the cooperative pool, never the main actor, so the
    /// expensive decode stays off the main thread. Returns `nil` for a missing/invalid hash.
    private nonisolated static func decode(blurHash: String?, size: CGSize, punch: Float) async -> UIImage? {
        guard let blurHash else { return nil }
        return UIImage(blurHash: blurHash, size: size, punch: punch)
    }
}

// MARK: - Preview

#Preview("Valid BlurHash") {
    BlurHashView(blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj")
        .frame(width: 200, height: 300)
        .clipShape(RoundedRectangle(cornerRadius: 12))
}

#Preview("Nil BlurHash") {
    BlurHashView(blurHash: nil)
        .frame(width: 200, height: 300)
        .clipShape(RoundedRectangle(cornerRadius: 12))
}
