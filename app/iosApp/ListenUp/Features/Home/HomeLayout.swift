import CoreGraphics

/// How Home spends the width it is given — pure, so the responsive contract is tested rather than
/// buried in view code (iosApp rule 12).
///
/// Home used to cap itself at a 700pt column on iPad, which left a 13-inch display two-thirds empty
/// ("a phone layout stretched on a tablet"). Now the rails bleed the full width at every size, the
/// cards grow with the window, and a wide window puts the week's stats beside the shelves instead
/// of under them. HIG, Designing for iPadOS: "Take advantage of the large display to elevate the
/// content people care about".
struct HomeLayout: Equatable {
    enum Arrangement: Equatable {
        /// Everything in one column: header, continue rail, stats, shelves.
        case column
        /// Shelves and the stats card side by side under the continue rail.
        case statsBesideShelves(statsWidth: CGFloat)
    }

    let arrangement: Arrangement
    /// The screen's side margin; rails scroll under it, headers align to it.
    let margin: CGFloat
    /// The continue-listening cover size, grown from the width.
    let continueCardWidth: CGFloat

    /// The narrowest window that holds a useful shelves row beside the stats card.
    static let minimumSideBySideWidth: CGFloat = 900

    static func forWidth(_ width: CGFloat) -> HomeLayout {
        let margin: CGFloat = width >= 700 ? 32 : 20
        let cardWidth = min(max(width / 6, 140), 200).rounded()
        guard width >= minimumSideBySideWidth else {
            return HomeLayout(arrangement: .column, margin: margin, continueCardWidth: cardWidth)
        }
        let statsWidth = min(max(width * 0.34, 320), 420).rounded()
        return HomeLayout(
            arrangement: .statsBesideShelves(statsWidth: statsWidth),
            margin: margin,
            continueCardWidth: cardWidth
        )
    }
}
