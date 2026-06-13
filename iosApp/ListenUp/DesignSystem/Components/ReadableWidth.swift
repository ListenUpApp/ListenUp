import SwiftUI

extension View {
    /// Caps content to a comfortable reading width and centers it, so single-column content
    /// (forms, placeholders, profile/search screens) doesn't stretch edge-to-edge on iPad and
    /// large/resized windows. A no-op when the container is already narrower than `maxWidth`,
    /// so it's safe to apply unconditionally — the cap only bites once there's excess width.
    func readableWidth(_ maxWidth: CGFloat = 640) -> some View {
        frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }
}
