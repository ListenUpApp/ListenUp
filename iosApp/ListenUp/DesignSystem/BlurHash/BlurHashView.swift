import SwiftUI

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

    var body: some View {
        if let blurHash,
           let uiImage = UIImage(blurHash: blurHash, size: size, punch: punch) {
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
        } else {
            // Fallback gradient for missing/invalid blurHash
            LinearGradient(
                colors: [Color.gray.opacity(0.3), Color.gray.opacity(0.2)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
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
