import SwiftUI

extension Color {
    // MARK: - Brand Colors

    /// ListenUp brand orange (#FF6B4A)
    static let listenUpOrange = Color(hex: "FF6B4A")

    /// Dark grey for gradient backgrounds (#1A1A1A)
    static let brandDarkGrey = Color(hex: "1A1A1A")

    // MARK: - Brand Gradient

    /// Brand gradient: Dark grey (top-left) to ListenUp orange (bottom-right)
    static var brandGradient: LinearGradient {
        LinearGradient(
            colors: [brandDarkGrey, listenUpOrange],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    // MARK: - Glass Effects

    /// Subtle border for native glass edge effect
    static let glassBorder = Color.white.opacity(0.3)
}
