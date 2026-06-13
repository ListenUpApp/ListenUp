import SwiftUI

/// Shared metrics + backdrop tints for the auth design system.
enum AuthMetrics {
    static let cardCornerRadius: CGFloat = 28
    static let cardMaxWidth: CGFloat = 420
    static let fieldGroupCornerRadius: CGFloat = 12
    static let contentHorizontalPadding: CGFloat = 20
}

extension Color {
    /// Warm base the aurora sits on — light: #F4EBE6, dark: #0B0A0C.
    static func auroraBase(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: "0B0A0C") : Color(hex: "F4EBE6")
    }

    /// Amber light accent woven into the aurora.
    static let auroraAmber = Color(hex: "F5C84B")

    /// Deep ember accent for the aurora core.
    static let auroraEmber = Color(hex: "B5472C")
}
