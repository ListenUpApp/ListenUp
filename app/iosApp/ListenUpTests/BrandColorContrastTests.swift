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
            String(format: "%02X%02X%02X",
                   Int((color.red * 255).rounded()), Int((color.green * 255).rounded()), Int((color.blue * 255).rounded()))
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
            #expect(accent.map { SRGBColor($0, resolvedFor: traits) } == coral(traits))
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
