import UIKit
@preconcurrency import Shared

extension CoverThemeStyle {
    /// iOS's tokens for the shared cover colour rule, read from the asset catalog so the rule harmonises the coral the
    /// app actually draws, and checks it against the label ink the app actually uses (`OnBrandCoral`, `.label`).
    static var listenUp: CoverThemeStyle {
        CoverThemeStyle(light: tokens(.light), dark: tokens(.dark))
    }

    private static func tokens(_ style: UIUserInterfaceStyle) -> CoverThemeTokens {
        let traits = UITraitCollection(userInterfaceStyle: style)
        return CoverThemeTokens(
            brandArgb: ArgbColor.pack(UIColor(resource: .brandCoral), for: traits),
            onBrandArgb: ArgbColor.pack(UIColor(resource: .onBrandCoral), for: traits),
            inkArgb: ArgbColor.pack(.label, for: traits),
            paperArgb: nil
        )
    }
}
