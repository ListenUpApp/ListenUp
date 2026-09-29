import SwiftUI
import Testing
import UIKit
@testable import ListenUp

@Suite("CoverTint.clamp — WCAG-solved, per appearance")
struct CoverTintExtractorTests {
    private let surfaces = CoverTint.Surfaces.system

    /// Every appearance, its surfaces, the contrast its tint is solved to, and the on-tint ink.
    private var appearances: [(name: String, traits: UITraitCollection, surfaces: [SRGBColor], target: Double)] {
        [
            ("light", .appearance(.light, contrast: .normal), surfaces.light, ContrastMinimum.text),
            ("dark", .appearance(.dark, contrast: .normal), surfaces.dark, ContrastMinimum.text),
            ("light+HC", .appearance(.light, contrast: .high), surfaces.lightHighContrast, CoverTint.highContrastTarget),
            ("dark+HC", .appearance(.dark, contrast: .high), surfaces.darkHighContrast, CoverTint.highContrastTarget)
        ]
    }

    /// Hue, saturation and brightness swept through the clamp: 24 hues × 6 saturations × 6
    /// brightnesses, including pure black, pure white, greys and fully saturated primaries.
    private static var sweep: [SRGBColor] {
        let steps = [0.0, 0.2, 0.4, 0.6, 0.8, 1.0]
        return (0..<24).flatMap { hueStep in
            steps.flatMap { saturation in
                steps.map { brightness in
                    SRGBColor(hue: Double(hueStep) / 24, saturation: saturation, brightness: brightness)
                }
            }
        }
    }

    @Test("every swept cover resolves to legible text (4.5:1) and glyphs (3:1) in light and dark")
    func sweepMeetsTextAndGlyphFloors() {
        for raw in Self.sweep {
            let tint = CoverTint.clamp(red: raw.red, green: raw.green, blue: raw.blue, surfaces: surfaces)
            for appearance in appearances {
                let resolved = tint.srgb(tint.resolution(for: appearance.traits))
                let ratio = resolved.minimumContrastRatio(against: appearance.surfaces)
                #expect(ratio >= ContrastMinimum.text, "\(raw) as text in \(appearance.name): \(ratio)")
                #expect(ratio >= ContrastMinimum.glyph, "\(raw) as glyph in \(appearance.name): \(ratio)")
                #expect(ratio >= appearance.target, "\(raw) below its \(appearance.name) target: \(ratio)")
            }
        }
    }

    @Test("the on-tint ink stays legible on a tint fill in every appearance")
    func onTintInkOnTintFill() {
        for raw in Self.sweep {
            let tint = CoverTint.clamp(red: raw.red, green: raw.green, blue: raw.blue, surfaces: surfaces)
            for appearance in appearances {
                let fill = tint.srgb(tint.resolution(for: appearance.traits))
                let ink = SRGBColor(.listenUpOnOrange, resolvedFor: appearance.traits)
                let ratio = ink.contrastRatio(against: fill)
                #expect(ratio >= ContrastMinimum.text, "on-tint on \(raw) in \(appearance.name): \(ratio)")
            }
        }
    }

    @Test("the dynamic colour resolves to each appearance's own shade")
    func dynamicColourResolvesPerAppearance() {
        let tint = CoverTint.clamp(red: 0.70, green: 0.18, blue: 0.30, surfaces: surfaces)
        for appearance in appearances {
            let resolved = SRGBColor(tint.uiColor, resolvedFor: appearance.traits)
            let expected = tint.srgb(tint.resolution(for: appearance.traits))
            #expect(abs(resolved.red - expected.red) < 0.002)
            #expect(abs(resolved.green - expected.green) < 0.002)
            #expect(abs(resolved.blue - expected.blue) < 0.002)
        }
        // Light darkens and dark lightens, so the two shades differ.
        #expect(tint.light.brightness < tint.dark.brightness || tint.light.saturation != tint.dark.saturation)
    }

    @Test("a vivid colour keeps its hue and its saturation stays in the subtle band")
    func vividKeepsHue() {
        var expectedHue: CGFloat = 0, sat: CGFloat = 0, bri: CGFloat = 0, alpha: CGFloat = 0
        UIColor(red: 0.70, green: 0.18, blue: 0.30, alpha: 1)
            .getHue(&expectedHue, saturation: &sat, brightness: &bri, alpha: &alpha)
        let tint = CoverTint.clamp(red: 0.70, green: 0.18, blue: 0.30, surfaces: surfaces)
        #expect(abs(tint.hue - Double(expectedHue)) < 0.001)
        #expect(tint.light.saturation <= CoverTint.maxSaturation + 0.001)
        #expect(tint.light.saturation >= CoverTint.minSaturation - 0.001)
    }

    @Test("a fully desaturated grey is floored to a minimum saturation in light")
    func greyGetsSaturationFloor() {
        let tint = CoverTint.clamp(red: 0.5, green: 0.5, blue: 0.5, surfaces: surfaces)
        #expect(tint.light.saturation >= CoverTint.minSaturation - 0.001)
    }

    @Test("pure blue — the darkest hue — gives up saturation in dark rather than contrast")
    func pureBlueInDark() {
        let tint = CoverTint.clamp(red: 0, green: 0, blue: 1, surfaces: surfaces)
        #expect(tint.srgb(tint.dark).minimumContrastRatio(against: surfaces.dark) >= ContrastMinimum.text)
        #expect(tint.dark.brightness == 1)
        #expect(tint.dark.saturation < CoverTint.maxSaturation)
    }

    @Test("the extractor caches per book and keeps the coral fallback on an undecodable cover")
    @MainActor
    func extractorFallsBackAndCaches() async {
        let extractor = CoverTintExtractor()
        let missing = await extractor.resolve(bookId: "no-cover", coverPath: "/nonexistent/cover.jpg")
        #expect(missing == nil)
        #expect(extractor.cached(bookId: "no-cover") == nil)
        let noPath = await extractor.resolve(bookId: "no-path", coverPath: nil)
        #expect(noPath == nil)
    }
}
