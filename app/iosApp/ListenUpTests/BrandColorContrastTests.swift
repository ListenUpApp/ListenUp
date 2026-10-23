import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// Pins the brand Color Sets against the HIG's contrast floors in every appearance
/// (HIG, Accessibility: 4.5:1 for text up to 17pt, 3:1 for bold/large text and glyphs; HIG, Color:
/// light, dark, and an increased-contrast option for each).
@Suite("Brand colour contrast")
struct BrandColorContrastTests {
    private typealias Traits = UITraitCollection

    private let lightDefault = Traits.appearance(.light, contrast: .normal)
    private let darkDefault = Traits.appearance(.dark, contrast: .normal)
    private let lightHigh = Traits.appearance(.light, contrast: .high)
    private let darkHigh = Traits.appearance(.dark, contrast: .high)

    private func coral(_ traits: Traits) -> SRGBColor { SRGBColor(.listenUpOrange, resolvedFor: traits) }
    private func onCoral(_ traits: Traits) -> SRGBColor { SRGBColor(.listenUpOnOrange, resolvedFor: traits) }
    private func surface(_ color: UIColor, _ traits: Traits) -> SRGBColor { SRGBColor(color, resolvedFor: traits) }

    /// Every surface coral text sits on: plain and grouped backgrounds at all three levels.
    private func surfaces(_ traits: Traits) -> [SRGBColor] {
        [
            UIColor.systemBackground, .secondarySystemBackground, .tertiarySystemBackground,
            .systemGroupedBackground, .secondarySystemGroupedBackground, .tertiarySystemGroupedBackground
        ].map { surface($0, traits) }
    }

    @Test("the Color Set carries the decided values")
    func decidedValues() {
        func hex(_ color: SRGBColor) -> String {
            let channels = [color.red, color.green, color.blue].map { Int(($0 * 255).rounded()) }
            return String(format: "%02lX%02lX%02lX", channels[0], channels[1], channels[2])
        }
        #expect(hex(coral(lightDefault)) == "D73812")
        #expect(hex(coral(darkDefault)) == "FF6A3D")
        #expect(hex(coral(lightHigh)) == "B02A0A")
        #expect(hex(coral(darkHigh)) == "FF9270")
    }

    @Test("the global accent is the brand coral in every appearance")
    func accentIsBrandCoral() {
        let accent = UIColor(named: "AccentColor")
        #expect(accent != nil)
        for traits in Traits.colorAppearances {
            let resolved = accent.map { SRGBColor($0, resolvedFor: traits) } ?? SRGBColor(red: 0, green: 0, blue: 0)
            let expected = coral(traits)
            #expect(abs(resolved.red - expected.red) < 0.002)
            #expect(abs(resolved.green - expected.green) < 0.002)
            #expect(abs(resolved.blue - expected.blue) < 0.002)
        }
    }

    @Test("text on a coral fill meets 4.5:1 in every appearance")
    func onCoralText() {
        for traits in Traits.colorAppearances {
            let ratio = onCoral(traits).contrastRatio(against: coral(traits))
            #expect(ratio >= ContrastMinimum.text, "on-coral \(ratio) in \(traits.userInterfaceStyle.rawValue)")
        }
    }

    @Test("dark coral text meets 4.5:1 on every dark surface, default and increased contrast")
    func darkCoralText() {
        for traits in [darkDefault, darkHigh] {
            let ratio = coral(traits).minimumContrastRatio(against: surfaces(traits))
            #expect(ratio >= ContrastMinimum.text, "dark coral text \(ratio)")
        }
    }

    @Test("light coral text meets 4.5:1 on the white surfaces, and on every surface under Increase Contrast")
    func lightCoralText() {
        let white = [UIColor.systemBackground, .secondarySystemGroupedBackground].map { surface($0, lightDefault) }
        #expect(coral(lightDefault).minimumContrastRatio(against: white) >= ContrastMinimum.text)
        #expect(coral(lightHigh).minimumContrastRatio(against: surfaces(lightHigh)) >= ContrastMinimum.text)
    }

    /// The one known gap, kept honest rather than hidden: the decided light coral `#D73812` (shared
    /// with Android, #1499) is 4.22:1 on the grey grouped canvas `#F2F2F7`. It clears the 3:1 the HIG
    /// sets for bold and large text, and Increase Contrast swaps in `#B02A0A` (5.92:1) — the HIG's
    /// stated remedy: "If your app doesn't provide this minimum contrast by default, ensure it at
    /// least provides a higher contrast color scheme when the system setting Increase Contrast is
    /// turned on."
    @Test("light coral on the grouped canvas clears the bold-text floor by default")
    func lightCoralOnGroupedCanvas() {
        let canvas = surface(.systemGroupedBackground, lightDefault)
        let ratio = coral(lightDefault).contrastRatio(against: canvas)
        #expect(ratio >= ContrastMinimum.largeText)
        #expect(ratio < ContrastMinimum.text, "if this now passes 4.5:1, drop this carve-out")
    }

    @Test("a tonal coral chip keeps its coral glyph at 3:1 on the row surface")
    func tonalChip() {
        // IconTile `.tonal` is 0.14, the capsule chips 0.12 — take the denser wash, the worse case.
        for traits in Traits.colorAppearances {
            let row = surface(.secondarySystemGroupedBackground, traits)
            let chip = coral(traits).composited(opacity: 0.14, over: row)
            let ratio = coral(traits).contrastRatio(against: chip)
            #expect(ratio >= ContrastMinimum.glyph, "tonal chip \(ratio)")
        }
    }

    @Test("the neutral IconTile keeps its glyph at 3:1 on the row surface")
    func neutralIconTile() {
        // `IconTile` with no tint: a secondary-label glyph on `luFill` (tertiarySystemFill).
        for traits in Traits.colorAppearances {
            let row = surface(.secondarySystemGroupedBackground, traits)
            let tile = SRGBColor(.tertiarySystemFill, resolvedFor: traits, over: row)
            let glyph = SRGBColor(.secondaryLabel, resolvedFor: traits, over: tile)
            let ratio = glyph.contrastRatio(against: tile)
            #expect(ratio >= ContrastMinimum.glyph, "neutral tile \(ratio)")
        }
    }

    @Test("the warning amber reads as text on every surface, and inside its own tonal pill")
    func warningAmber() {
        for traits in Traits.colorAppearances {
            let amber = SRGBColor(UIColor(resource: .warningAmber), resolvedFor: traits)
            let ratio = amber.minimumContrastRatio(against: surfaces(traits))
            #expect(ratio >= ContrastMinimum.text, "warning text \(ratio)")
            // The import review rows' "Needs review" pill: amber text on a 16% amber wash.
            let row = surface(.secondarySystemGroupedBackground, traits)
            let pill = amber.composited(opacity: 0.16, over: row)
            #expect(amber.contrastRatio(against: pill) >= ContrastMinimum.largeText, "warning pill")
        }
    }

    @Test("the player's error red reads as text on every surface, in every appearance")
    func playerErrorRed() {
        let red = UIColor(named: "PlayerErrorRed")
        #expect(red != nil)
        guard let red else { return }
        for traits in Traits.colorAppearances {
            let ratio = SRGBColor(red, resolvedFor: traits).minimumContrastRatio(against: surfaces(traits))
            let style = traits.userInterfaceStyle.rawValue
            #expect(ratio >= ContrastMinimum.text, "player error text \(ratio) in \(style)")
        }
    }

    @Test("a tinted capsule's coral ink reads as text on its 12% tint, in every appearance")
    func coralInkOnTint() {
        let ink = UIColor(named: "CoralInk")
        #expect(ink != nil)
        guard let ink else { return }
        for traits in Traits.colorAppearances {
            for base in [UIColor.systemBackground, .secondarySystemGroupedBackground] {
                let tint = coral(traits).composited(opacity: 0.12, over: surface(base, traits))
                let ratio = SRGBColor(ink, resolvedFor: traits).contrastRatio(against: tint)
                #expect(ratio >= ContrastMinimum.text, "coral ink \(ratio) in \(traits.userInterfaceStyle.rawValue)")
            }
        }
    }

    /// Now Playing's armed sleep timer: the countdown in the sleep gold's ink on its container (player spec §3.9).
    @Test("the sleep gold's ink reads as text on its container, in every appearance")
    func sleepGold() {
        let container = UIColor(named: "SleepGoldContainer")
        let ink = UIColor(named: "SleepGoldInk")
        #expect(container != nil && ink != nil)
        guard let container, let ink else { return }
        for traits in Traits.colorAppearances {
            let ratio = SRGBColor(ink, resolvedFor: traits).contrastRatio(against: SRGBColor(container, resolvedFor: traits))
            #expect(ratio >= ContrastMinimum.text, "sleep gold \(ratio) in \(traits.userInterfaceStyle.rawValue)")
        }
        func hex(_ color: UIColor) -> String {
            let srgb = SRGBColor(color, resolvedFor: lightDefault)
            let channels = [srgb.red, srgb.green, srgb.blue].map { Int(($0 * 255).rounded()) }
            return String(format: "%02lX%02lX%02lX", channels[0], channels[1], channels[2])
        }
        #expect(hex(container) == "F6E1C4")
        #expect(hex(ink) == "4A2E00")
    }

    /// The running timer's ring and bar: the meter against the gold track it runs in, as a meaningful graphic (3:1), and
    /// the fade card's Keep Listening, gold ink on the gold's own dark ink (player spec §5.6).
    @Test("the sleep gold's meter reads on its track, and Keep Listening's ink on its fill, in every appearance")
    func sleepGoldMeter() {
        let container = UIColor(named: "SleepGoldContainer")
        let ink = UIColor(named: "SleepGoldInk")
        let meter = UIColor(named: "SleepGoldMeter")
        #expect(container != nil && ink != nil && meter != nil)
        guard let container, let ink, let meter else { return }
        for traits in Traits.colorAppearances {
            let track = SRGBColor(container, resolvedFor: traits)
            let meterRatio = SRGBColor(meter, resolvedFor: traits).contrastRatio(against: track)
            #expect(meterRatio >= ContrastMinimum.glyph, "meter \(meterRatio) in \(traits.userInterfaceStyle.rawValue)")
            let buttonRatio = track.contrastRatio(against: SRGBColor(ink, resolvedFor: traits))
            #expect(buttonRatio >= ContrastMinimum.text, "keep \(buttonRatio) in \(traits.userInterfaceStyle.rawValue)")
        }
    }

    /// The running Sleep sheet's capsules: Turn Off Timer's danger ink and +5 / +10 / +15's coral ink on the neutral
    /// capsule fill, over every surface a sheet can sit on, base and elevated (player spec §8; HIG, Accessibility).
    @Test("the sleep sheet's capsule inks read as text on their fill, in every appearance")
    func sheetCapsuleInks() {
        let danger = UIColor(named: "DangerInk")
        let coralInk = UIColor(named: "CoralInk")
        #expect(danger != nil && coralInk != nil)
        guard let danger, let coralInk else { return }
        for traits in Traits.colorAppearances {
            for level in [UIUserInterfaceLevel.base, .elevated] {
                let resolved = traits.modifyingTraits { $0.userInterfaceLevel = level }
                for base in [UIColor.systemBackground, .secondarySystemBackground] {
                    let capsule = SRGBColor(.tertiarySystemFill, resolvedFor: resolved, over: surface(base, resolved))
                    let style = "\(traits.userInterfaceStyle.rawValue)/\(level.rawValue)"
                    let off = SRGBColor(danger, resolvedFor: resolved).contrastRatio(against: capsule)
                    #expect(off >= ContrastMinimum.text, "turn off \(off) in \(style)")
                    let extend = SRGBColor(coralInk, resolvedFor: resolved).contrastRatio(against: capsule)
                    #expect(extend >= ContrastMinimum.text, "extend \(extend) in \(style)")
                }
            }
        }
    }

    @Test("the warning amber is visibly not the brand coral")
    func warningIsNotCoral() {
        func hue(_ color: UIColor) -> Double {
            var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0, alpha: CGFloat = 0
            color.resolvedColor(with: lightDefault)
                .getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha)
            return Double(hue)
        }
        let coralHue = hue(.listenUpOrange)
        let amberHue = hue(UIColor(resource: .warningAmber))
        // More than 20° apart on the wheel (coral ≈ 12°, amber ≈ 40°).
        #expect(abs(amberHue - coralHue) * 360 > 20)
    }
}
