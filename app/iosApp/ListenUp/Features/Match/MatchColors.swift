import SwiftUI
import UIKit

/// Match details' two signal colours, each with a word or a glyph beside it so no state is told by
/// colour alone. Both pass 4.5:1 in light and dark (`MatchColorContrastTests`).
extension UIColor {
    /// The Strong match check and reasons: green on the grouped row.
    static let matchStrong = UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(rgb: 0x5DD27A) : UIColor(rgb: 0x1B7A33)
    }

    /// "You edited this": #8A6100 on #FFF4D6 (the spec's pair), and its dark counterpart.
    static let matchEditedInk = UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(rgb: 0xFFD27A) : UIColor(rgb: 0x8A6100)
    }

    static let matchEditedFill = UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(rgb: 0x3A2C00) : UIColor(rgb: 0xFFF4D6)
    }

    fileprivate convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    static let luStrongMatch = Color(uiColor: .matchStrong)
    static let luEditedInk = Color(uiColor: .matchEditedInk)
    static let luEditedFill = Color(uiColor: .matchEditedFill)
}
