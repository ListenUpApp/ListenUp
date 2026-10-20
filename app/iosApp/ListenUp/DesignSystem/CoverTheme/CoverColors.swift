import SwiftUI
import UIKit
@preconcurrency import Shared

/// A cover's colours for a hero (Book Detail spec §2.3) as dynamic colours: each resolves per appearance, so a switch to
/// dark needs no new work. Under Increase Contrast the action keeps the asset catalog's high-contrast coral,
/// unharmonised; the wash stays (ink on it is far above 4.5:1).
struct CoverColors: Equatable {
    let wash: Color
    let track: Color
    let action: Color
    /// What the colours were built from. Dynamic `Color`s never compare equal, so equality (which drives the
    /// crossfade's `animation(value:)`) compares these instead.
    private let source: [Int32]

    init(_ themes: CoverThemes) {
        let light = themes.light, dark = themes.dark
        wash = Self.dynamic(light: light.washArgb, dark: dark.washArgb)
        track = Self.dynamic(light: light.trackArgb, dark: dark.trackArgb)
        action = Color(uiColor: UIColor { traits in
            if traits.accessibilityContrast == .high {
                return UIColor(resource: .brandCoral).resolvedColor(with: traits)
            }
            return ArgbColor.uiColor(traits.userInterfaceStyle == .dark ? dark.actionArgb : light.actionArgb)
        })
        source = [light.washArgb, light.trackArgb, light.actionArgb, dark.washArgb, dark.trackArgb, dark.actionArgb]
    }

    static func == (lhs: CoverColors, rhs: CoverColors) -> Bool { lhs.source == rhs.source }

    private static func dynamic(light: Int32, dark: Int32) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? ArgbColor.uiColor(dark) : ArgbColor.uiColor(light) })
    }
}
