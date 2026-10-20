import UIKit

/// Kotlin's ARGB `Int` crosses Swift Export as `Int32`. Opaque colours have the top bit set, so they are negative:
/// packing and unpacking go through the bit pattern, never a numeric conversion.
enum ArgbColor {
    /// `color` resolved for `traits`, packed as ARGB.
    static func pack(_ color: UIColor, for traits: UITraitCollection) -> Int32 {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.resolvedColor(with: traits).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        func byte(_ value: CGFloat) -> UInt32 { UInt32((min(max(value, 0), 1) * 255).rounded()) }
        return Int32(bitPattern: byte(alpha) << 24 | byte(red) << 16 | byte(green) << 8 | byte(blue))
    }

    /// ARGB as an sRGB colour.
    static func uiColor(_ argb: Int32) -> UIColor {
        let bits = UInt32(bitPattern: argb)
        func unit(_ shift: UInt32) -> CGFloat { CGFloat((bits >> shift) & 0xFF) / 255 }
        return UIColor(red: unit(16), green: unit(8), blue: unit(0), alpha: unit(24))
    }
}
