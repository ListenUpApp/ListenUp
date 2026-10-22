import SwiftUI
import Testing
import UIKit
@testable import ListenUp

@Suite("Now Playing's wash: the cover's, or the player's neutral")
@MainActor
struct NowPlayingWashTests {
    @Test("no cover colours: the player's neutral, in light and in dark")
    func neutral() {
        #expect(FullScreenPlayerView.wash(nil, scheme: .light) == PlayerWashTone.color(for: .light))
        #expect(FullScreenPlayerView.wash(nil, scheme: .dark) == PlayerWashTone.color(for: .dark))
    }

    @Test("the light neutral is the spec's #F5EFE9, and the dark one the system background")
    func decidedValues() {
        let lightTraits = UITraitCollection(userInterfaceStyle: .light)
        let light = SRGBColor(UIColor(PlayerWashTone.color(for: .light)), resolvedFor: lightTraits)
        let channels = [light.red, light.green, light.blue].map { Int(($0 * 255).rounded()) }
        #expect(channels == [0xF5, 0xEF, 0xE9])
        let dark = UITraitCollection(userInterfaceStyle: .dark)
        let resolved = SRGBColor(UIColor(PlayerWashTone.color(for: .dark)), resolvedFor: dark)
        let system = SRGBColor(.systemBackground, resolvedFor: dark)
        #expect(abs(resolved.red - system.red) < 0.002 && abs(resolved.blue - system.blue) < 0.002)
    }

    @Test("the state card's fill stands apart from every wash it sits on, in light and in dark")
    func cardHasAnEdge() {
        for (style, covers) in [(UIUserInterfaceStyle.light, Self.lightCoverWashes), (.dark, Self.darkCoverWashes)] {
            let traits = UITraitCollection(userInterfaceStyle: style)
            let fill = SRGBColor(UIColor(PlayerStateCardView.fill), resolvedFor: traits)
            let neutral = SRGBColor(
                UIColor(PlayerWashTone.color(for: style == .dark ? .dark : .light)), resolvedFor: traits)
            for wash in [neutral] + covers.map(Self.srgb) {
                #expect(fill.contrastRatio(against: wash) >= 1.1, "card on \(wash) in \(style.rawValue)")
            }
        }
    }

    @Test("secondary ink reads at 4.5:1 and tertiary at 3:1 on the neutral and on every cover wash")
    func inkOnTheWash() {
        for traits in UITraitCollection.colorAppearances {
            let isDark = traits.userInterfaceStyle == .dark
            let neutral = SRGBColor(UIColor(PlayerWashTone.color(for: isDark ? .dark : .light)), resolvedFor: traits)
            let washes = [neutral] + (isDark ? Self.darkCoverWashes : Self.lightCoverWashes).map(Self.srgb)
            let secondary = SRGBColor(UIColor(Color.luOnWashSecondary), resolvedFor: traits)
            let tertiary = SRGBColor(UIColor(Color.luOnWashTertiary), resolvedFor: traits)
            #expect(secondary.minimumContrastRatio(against: washes) >= ContrastMinimum.text, "secondary \(traits)")
            #expect(tertiary.minimumContrastRatio(against: washes) >= ContrastMinimum.glyph, "tertiary \(traits)")
        }
    }

    /// The shared cover rule's washes: tone 91 at chroma 6 in light, tone 20 at chroma 8 in dark, around the hue
    /// circle. A tone fixes the luminance, so these stand for every cover the rule can give.
    static let lightCoverWashes = ["F1E1E5", "F1E2DD", "EAE5DA", "E0E7DD", "D9E9E5", "D8E8ED", "E0E6F1", "EAE3ED"]
    static let darkCoverWashes = ["3C2C31", "3B2D28", "353025", "2B3228", "223430", "213338", "2A313C", "352E39"]

    static func srgb(_ hex: String) -> SRGBColor {
        let value = UInt32(hex, radix: 16) ?? 0
        return SRGBColor(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }

    @Test("label ink on the light neutral passes 4.5:1")
    func contrast() {
        let traits = UITraitCollection(userInterfaceStyle: .light)
        let wash = SRGBColor(UIColor(PlayerWashTone.color(for: .light)), resolvedFor: traits)
        #expect(SRGBColor(.label, resolvedFor: traits).contrastRatio(against: wash) >= ContrastMinimum.text)
    }
}
