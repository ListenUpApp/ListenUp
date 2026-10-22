import SwiftUI
import UIKit

/// A legibility-clamped per-book accent derived from cover artwork.
///
/// The cover's hue is kept and its saturation held in a subtle band (never garish per the iOS
/// design language, never grey mud). Brightness is then solved *separately for each appearance*
/// until the tint meets WCAG contrast against every system surface of that appearance: the
/// brightest legible shade for light, the deepest legible shade for dark. The result is one
/// dynamic colour (`UIColor { traits in }`) that reads as text in every appearance, so the same
/// tint can colour a glyph, a slider or a label — and `Color.luOnTint` stays legible on a tint fill
/// (the tests pin that too).
///
/// HIG, Color: "Make sure all your app's colors work well in light, dark, and increased contrast
/// contexts." HIG, Accessibility: 4.5:1 for text up to 17pt, and a higher-contrast scheme when
/// Increase Contrast is on — so the increased-contrast resolutions aim for 7:1, the ratio HIG,
/// Dark Mode asks custom colours to strive for.
struct CoverTint: Equatable, Sendable {
    /// One appearance's resolution of the tint: the hue's saturation and brightness there.
    struct Resolution: Equatable, Sendable {
        let saturation: Double
        let brightness: Double
    }

    let hue: Double
    let light: Resolution
    let dark: Resolution
    let lightHighContrast: Resolution
    let darkHighContrast: Resolution

    static let minSaturation = 0.25
    static let maxSaturation = 0.60
    /// The contrast the default appearances solve to: WCAG AA for body text.
    static let targetContrast = ContrastMinimum.text
    /// The contrast the Increase Contrast appearances solve to.
    static let highContrastTarget = 7.0

    /// The colour of one resolution.
    func srgb(_ resolution: Resolution) -> SRGBColor {
        SRGBColor(hue: hue, saturation: resolution.saturation, brightness: resolution.brightness)
    }

    /// The resolution `traits` selects.
    func resolution(for traits: UITraitCollection) -> Resolution {
        switch (traits.userInterfaceStyle == .dark, traits.accessibilityContrast == .high) {
        case (false, false): light
        case (true, false): dark
        case (false, true): lightHighContrast
        case (true, true): darkHighContrast
        }
    }

    /// The tint as a dynamic colour: each appearance resolves to its own solved shade.
    var uiColor: UIColor {
        let tint = self
        return UIColor { traits in tint.srgb(tint.resolution(for: traits)).uiColor }
    }

    var color: Color { Color(uiColor: uiColor) }

    /// Clamps an averaged cover colour against the system surfaces of every appearance.
    static func clamp(red: Double, green: Double, blue: Double) -> CoverTint {
        clamp(red: red, green: green, blue: blue, surfaces: .system)
    }

    /// Clamps against explicit `surfaces` — the pure core the tests sweep.
    static func clamp(red: Double, green: Double, blue: Double, surfaces: Surfaces) -> CoverTint {
        let hsb = hsb(red: red, green: green, blue: blue)
        let saturation = min(max(hsb.saturation, minSaturation), maxSaturation)
        return CoverTint(
            hue: hsb.hue,
            light: darken(hue: hsb.hue, saturation: saturation, against: surfaces.light, target: targetContrast),
            dark: lighten(hue: hsb.hue, saturation: saturation, against: surfaces.dark, target: targetContrast),
            lightHighContrast: darken(
                hue: hsb.hue, saturation: saturation, against: surfaces.lightHighContrast, target: highContrastTarget
            ),
            darkHighContrast: lighten(
                hue: hsb.hue, saturation: saturation, against: surfaces.darkHighContrast, target: highContrastTarget
            )
        )
    }

    /// The step the solves walk in: fine enough to land within a hair of the target, and bounded
    /// (at most 100 steps per walk).
    private static let step = 0.01

    /// Light appearance: from full brightness, step down until the tint clears `target` on every
    /// background — the brightest legible shade. Black clears 21:1, so the walk always ends.
    private static func darken(
        hue: Double, saturation: Double, against backgrounds: [SRGBColor], target: Double
    ) -> Resolution {
        var brightness = 1.0
        while brightness > 0,
              SRGBColor(hue: hue, saturation: saturation, brightness: brightness)
                .minimumContrastRatio(against: backgrounds) < target {
            brightness = max(0, brightness - step)
        }
        return Resolution(saturation: saturation, brightness: brightness)
    }

    /// Dark appearance: from black, step brightness up until the tint clears `target` — the
    /// deepest legible shade. A deep hue (pure blue) can still fall short at full brightness, so
    /// saturation then steps down toward white, which clears every dark surface.
    private static func lighten(
        hue: Double, saturation: Double, against backgrounds: [SRGBColor], target: Double
    ) -> Resolution {
        func passes(_ saturation: Double, _ brightness: Double) -> Bool {
            SRGBColor(hue: hue, saturation: saturation, brightness: brightness)
                .minimumContrastRatio(against: backgrounds) >= target
        }
        var brightness = 0.0
        while brightness < 1, !passes(saturation, brightness) {
            brightness = min(1, brightness + step)
        }
        var saturation = saturation
        while saturation > 0, !passes(saturation, brightness) {
            saturation = max(0, saturation - step)
        }
        return Resolution(saturation: saturation, brightness: brightness)
    }

    private static func hsb(
        red: Double, green: Double, blue: Double
    ) -> (hue: Double, saturation: Double, brightness: Double) {
        let maximum = max(red, green, blue)
        let delta = maximum - min(red, green, blue)
        guard delta > 0 else { return (0, 0, maximum) }
        let sector: Double
        if maximum == red {
            sector = (green - blue) / delta
        } else if maximum == green {
            sector = (blue - red) / delta + 2
        } else {
            sector = (red - green) / delta + 4
        }
        let hue = sector / 6
        return (hue < 0 ? hue + 1 : hue, delta / maximum, maximum)
    }
}

extension CoverTint {
    /// The opaque surfaces a tint must read on, per appearance.
    struct Surfaces: Sendable {
        let light: [SRGBColor]
        let dark: [SRGBColor]
        let lightHighContrast: [SRGBColor]
        let darkHighContrast: [SRGBColor]

        /// The system and grouped backgrounds at all three levels, resolved for each appearance.
        static var system: Surfaces {
            let colors: [UIColor] = [
                .systemBackground, .secondarySystemBackground, .tertiarySystemBackground,
                .systemGroupedBackground, .secondarySystemGroupedBackground, .tertiarySystemGroupedBackground
            ]
            func resolved(_ style: UIUserInterfaceStyle, _ contrast: UIAccessibilityContrast) -> [SRGBColor] {
                let traits = UITraitCollection.appearance(style, contrast: contrast)
                return colors.map { SRGBColor($0, resolvedFor: traits) }
            }
            return Surfaces(
                light: resolved(.light, .normal),
                dark: resolved(.dark, .normal),
                lightHighContrast: resolved(.light, .high),
                darkHighContrast: resolved(.dark, .high)
            )
        }
    }
}
