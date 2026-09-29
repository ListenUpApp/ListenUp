import SwiftUI
import UIKit

extension Color {
    // MARK: - Brand Colors

    /// ListenUp brand coral — the single action tint, app-wide. The `BrandCoral` Color Set, which
    /// is also the catalog's `AccentColor`:
    ///
    /// | | Any | Dark |
    /// |---|---|---|
    /// | Default | `#D73812` | `#FF6A3D` |
    /// | Increased contrast | `#B02A0A` | `#FF9270` |
    ///
    /// HIG, Color: "If you define a custom color, make sure to supply light and dark variants, and an
    /// increased contrast option for each variant". Light `#D73812` carries white text at 4.70:1 and
    /// reads as text on the white row surface at 4.70:1; dark `#FF6A3D` reads on `#2C2C2E` at 4.90:1.
    /// `BrandColorContrastTests` pins every pair. (Xcode's generated asset symbols already name the
    /// sets `brandCoral`/`onBrandCoral`, so the tokens here keep their own names.)
    static let listenUpOrange = Color(ColorResource.brandCoral)

    /// Dark grey for gradient backgrounds (#1A1A1A)
    static let brandDarkGrey = Color(hex: "1A1A1A")

    // MARK: - Brand Gradient

    /// Brand gradient: Dark grey (top-left) to ListenUp orange (bottom-right)
    static var brandGradient: LinearGradient {
        LinearGradient(
            colors: [brandDarkGrey, listenUpOrange],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    // MARK: - Glass Effects

    /// Subtle border for native glass edge effect
    static let glassBorder = Color.white.opacity(0.3)
}

extension UIColor {
    /// The `BrandCoral` Color Set as a dynamic `UIColor`, for UIKit surfaces and trait resolution.
    static var listenUpOrange: UIColor { UIColor(resource: .brandCoral) }
    /// The `OnBrandCoral` Color Set as a dynamic `UIColor` — see `Color.luOnTint`.
    static var listenUpOnOrange: UIColor { UIColor(resource: .onBrandCoral) }
}
