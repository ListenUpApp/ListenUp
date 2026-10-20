import SwiftUI
import Testing
import UIKit
@preconcurrency import Shared
@testable import ListenUp
@Suite("CoverColors")
struct CoverColorsTests {
    private let themes = CoverThemes(
        light: CoverTheme(washArgb: Int32(bitPattern: 0xFFE4_E5EB), trackArgb: Int32(bitPattern: 0xFFEB_D3AE),
                          actionArgb: Int32(bitPattern: 0xFFCC_2F2A), onActionArgb: Int32(bitPattern: 0xFFFF_FFFF)),
        dark: CoverTheme(washArgb: Int32(bitPattern: 0xFF2B_2D33), trackArgb: Int32(bitPattern: 0xFF4A_3A22),
                         actionArgb: Int32(bitPattern: 0xFFFF_6045), onActionArgb: Int32(bitPattern: 0xFF1C_0A04))
    )

    private func resolved(_ color: Color, _ style: UIUserInterfaceStyle, _ contrast: UIAccessibilityContrast) -> Int32 {
        ArgbColor.pack(UIColor(color), for: UITraitCollection.appearance(style, contrast: contrast))
    }

    @Test func eachAppearanceResolvesToItsOwnColour() {
        let colors = CoverColors(themes)
        #expect(resolved(colors.wash, .light, .normal) == themes.light.washArgb)
        #expect(resolved(colors.wash, .dark, .normal) == themes.dark.washArgb)
        #expect(resolved(colors.track, .dark, .normal) == themes.dark.trackArgb)
        #expect(resolved(colors.action, .light, .normal) == themes.light.actionArgb)
    }

    @Test func increaseContrastKeepsTheAssetsCoralUnharmonised() {
        let colors = CoverColors(themes)
        #expect(resolved(colors.action, .light, .high) == Int32(bitPattern: 0xFFB0_2A0A))
        #expect(resolved(colors.action, .dark, .high) == Int32(bitPattern: 0xFFFF_9270))
    }

    @Test func equalityFollowsTheColoursNotTheProviders() {
        #expect(CoverColors(themes) == CoverColors(themes))
    }
}
