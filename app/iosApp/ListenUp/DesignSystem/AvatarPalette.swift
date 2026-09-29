import SwiftUI
import UIKit

/// The one avatar palette, app-wide: a person's initials in white on a filled circle whose colour is a
/// stable function of who they are.
///
/// Eight hues, spaced round the wheel and kept clear of the brand coral (≈12°) and the warning amber
/// (≈40°). Each hue's shade is *solved*, not picked: brightness steps down until white initials clear
/// 4.5:1 (7:1 under Increase Contrast), and the dark appearance takes a slightly quieter saturation.
/// HIG, Accessibility: 4.5:1 for text up to 17pt; HIG, Color: "supply light and dark variants, and an
/// increased contrast option for each". It replaces the per-screen hex lists, the random-per-launch
/// `hashValue` hue on contributors, and the fixed-brightness hue on users that left yellow initials at
/// 2.4:1.
enum AvatarPalette {
    /// Hue angles in degrees: blue, indigo, purple, magenta, rose, teal, green, olive.
    static let hueDegrees: [Double] = [215, 245, 275, 310, 340, 175, 145, 95]

    /// The initials' colour on every palette fill.
    static let initialsInk = Color.white

    /// The fill for `key` (a user or contributor id). Stable across launches and devices, and the
    /// same `Color` instance for the same slot, so equal keys compare equal.
    static func fill(forKey key: String) -> Color {
        fills[index(forKey: key)]
    }

    private static let fills: [Color] = Self.hueDegrees.map { Self.fill(hueDegrees: $0) }

    /// The fill at a hue of the caller's choosing — for an avatar whose colour is a status (the
    /// import review's matched green) rather than an identity.
    static func fill(hueDegrees: Double) -> Color {
        Color(uiColor: uiColor(hueDegrees: hueDegrees))
    }

    /// The palette slot `key` hashes to.
    static func index(forKey key: String) -> Int {
        Int(fnv1a(key) % UInt64(hueDegrees.count))
    }

    /// The fill as a dynamic `UIColor`: one solved shade per appearance.
    static func uiColor(hueDegrees: Double) -> UIColor {
        UIColor { traits in
            shade(
                hueDegrees: hueDegrees,
                isDark: traits.userInterfaceStyle == .dark,
                isHighContrast: traits.accessibilityContrast == .high
            ).uiColor
        }
    }

    /// The solved shade of a hue for one appearance — the pure core the tests sweep.
    static func shade(hueDegrees: Double, isDark: Bool, isHighContrast: Bool) -> SRGBColor {
        let white = SRGBColor(red: 1, green: 1, blue: 1)
        let target = isHighContrast ? 7.0 : ContrastMinimum.text
        let saturation = isDark ? 0.45 : 0.55
        var brightness = 1.0
        func candidate() -> SRGBColor {
            SRGBColor(hue: hueDegrees / 360, saturation: saturation, brightness: brightness)
        }
        while brightness > 0, white.contrastRatio(against: candidate()) < target {
            brightness = max(0, brightness - 0.01)
        }
        return candidate()
    }

    /// FNV-1a, not `String.hashValue`: Swift seeds `hashValue` per process, so a `hashValue` colour
    /// changes every launch. FNV-1a is stable across launches and machines.
    private static func fnv1a(_ key: String) -> UInt64 {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325 // FNV-1a 64-bit offset basis
        for byte in key.utf8 {
            hash = (hash ^ UInt64(byte)) &* 0x0000_0100_0000_01b3 // FNV prime
        }
        return hash
    }
}
