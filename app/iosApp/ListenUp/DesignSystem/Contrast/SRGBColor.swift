import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// An opaque sRGB colour with the WCAG 2.x contrast arithmetic the design system is held to.
///
/// HIG, Accessibility: "Strive to meet color contrast minimum standards" — 4.5:1 for text up to
/// 17pt, 3:1 for text at 18pt and above or bold. The same page asks for 3:1 for the icons and
/// glyphs that carry meaning, which is `ContrastMinimum.glyph`. Platform-neutral on purpose: the
/// maths is shared with the cover-tint clamp and the colour tests, and only the `UIColor` bridge
/// needs UIKit.
struct SRGBColor: Equatable, Sendable {
    let red: Double
    let green: Double
    let blue: Double

    init(red: Double, green: Double, blue: Double) {
        self.red = Self.unit(red)
        self.green = Self.unit(green)
        self.blue = Self.unit(blue)
    }

    /// The colour at `hue`/`saturation`/`brightness`, each 0…1 (the HSB model `UIColor` uses).
    init(hue: Double, saturation: Double, brightness: Double) {
        let hue = hue - hue.rounded(.down)
        let saturation = Self.unit(saturation)
        let brightness = Self.unit(brightness)
        let sector = hue * 6
        let fraction = sector - sector.rounded(.down)
        let low = brightness * (1 - saturation)
        let falling = brightness * (1 - saturation * fraction)
        let rising = brightness * (1 - saturation * (1 - fraction))
        switch Int(sector) % 6 {
        case 0: self.init(red: brightness, green: rising, blue: low)
        case 1: self.init(red: falling, green: brightness, blue: low)
        case 2: self.init(red: low, green: brightness, blue: rising)
        case 3: self.init(red: low, green: falling, blue: brightness)
        case 4: self.init(red: rising, green: low, blue: brightness)
        default: self.init(red: brightness, green: low, blue: falling)
        }
    }

    /// WCAG 2.x relative luminance: each channel linearised from sRGB, then weighted
    /// 0.2126 R + 0.7152 G + 0.0722 B.
    var relativeLuminance: Double {
        0.2126 * Self.linear(red) + 0.7152 * Self.linear(green) + 0.0722 * Self.linear(blue)
    }

    /// WCAG contrast ratio against `other`, from 1:1 (identical) to 21:1 (black on white).
    func contrastRatio(against other: SRGBColor) -> Double {
        let lighter = max(relativeLuminance, other.relativeLuminance)
        let darker = min(relativeLuminance, other.relativeLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /// The lowest contrast against any of `backgrounds` — the one that decides legibility.
    func minimumContrastRatio(against backgrounds: [SRGBColor]) -> Double {
        backgrounds.map(contrastRatio(against:)).min() ?? 21
    }

    /// This colour at `opacity`, flattened onto an opaque `background` (source-over in sRGB,
    /// which is how SwiftUI composites a `.opacity(_)` fill).
    func composited(opacity: Double, over background: SRGBColor) -> SRGBColor {
        let alpha = Self.unit(opacity)
        return SRGBColor(
            red: red * alpha + background.red * (1 - alpha),
            green: green * alpha + background.green * (1 - alpha),
            blue: blue * alpha + background.blue * (1 - alpha)
        )
    }

    private static func unit(_ value: Double) -> Double { min(max(value, 0), 1) }

    private static func linear(_ channel: Double) -> Double {
        channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
    }
}

/// The WCAG AA floors the HIG Accessibility page adopts.
enum ContrastMinimum {
    /// Body text up to 17pt.
    static let text = 4.5
    /// Text at 18pt and above, or bold at any size.
    static let largeText = 3.0
    /// Meaningful glyphs, icons and control boundaries.
    static let glyph = 3.0
}

#if canImport(UIKit)
extension SRGBColor {
    /// The sRGB value of an already-resolved `UIColor`. Extended-range components (a P3 system
    /// colour can step just outside 0…1) are clamped into sRGB; alpha is ignored — composite
    /// translucent colours with `composited(opacity:over:)` first.
    init(_ color: UIColor) {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        self.init(red: Double(red), green: Double(green), blue: Double(blue))
    }

    /// `color` resolved for `traits`, then read as sRGB.
    init(_ color: UIColor, resolvedFor traits: UITraitCollection) {
        self.init(color.resolvedColor(with: traits))
    }

    /// `color` resolved for `traits` and, if translucent (a system fill, a secondary label),
    /// flattened onto `background` by its own alpha — what the eye actually sees.
    init(_ color: UIColor, resolvedFor traits: UITraitCollection, over background: SRGBColor) {
        let resolved = color.resolvedColor(with: traits)
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        self = SRGBColor(red: Double(red), green: Double(green), blue: Double(blue))
            .composited(opacity: Double(alpha), over: background)
    }

    var uiColor: UIColor {
        UIColor(red: CGFloat(red), green: CGFloat(green), blue: CGFloat(blue), alpha: 1)
    }
}

extension UITraitCollection {
    /// The four appearances a dynamic colour must hold up in — HIG, Color: "supply light and dark
    /// variants, and an increased contrast option for each variant".
    static var colorAppearances: [UITraitCollection] {
        [
            appearance(.light, contrast: .normal),
            appearance(.dark, contrast: .normal),
            appearance(.light, contrast: .high),
            appearance(.dark, contrast: .high)
        ]
    }

    static func appearance(_ style: UIUserInterfaceStyle, contrast: UIAccessibilityContrast) -> UITraitCollection {
        UITraitCollection { traits in
            traits.userInterfaceStyle = style
            traits.accessibilityContrast = contrast
        }
    }
}
#endif
