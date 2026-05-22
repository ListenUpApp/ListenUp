import SwiftUI

extension Color {
    /// Initialize a Color from a hex string.
    ///
    /// Supports formats:
    /// - "FF6B4A" (RGB)
    /// - "#FF6B4A" (RGB with #)
    /// - "FFFF6B4A" (ARGB)
    /// - "#FFFF6B4A" (ARGB with #)
    ///
    /// Example:
    /// ```swift
    /// Color(hex: "FF6B4A")
    /// Color(hex: "#8B3A3A")
    /// ```
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)

        let a, r, g, b: UInt64
        switch hex.count {
        case 6: // RGB
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (255, 0, 0, 0)
        }

        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}
