import CoreGraphics

/// How Home spends the width it is given — pure, so the responsive contract is tested rather than buried in view
/// code (iosApp rule 12). The order of its sections is the shared `homeBlocks` rule.
///
/// A window narrower than `minimumWideWidth` is one column with a scrolling rail. A wider one (an iPad with its
/// sidebar is ~860pt of content) sets the stats card first at 380pt beside the shelves, or beside the slot when there
/// is one, and draws Everything in Progress as one row of up to five columns that flow with the width (board Home-iPad).
struct HomeLayout: Equatable {
    enum Arrangement: Equatable {
        case column
        case wide(statsWidth: CGFloat)
    }

    enum Rail: Equatable {
        /// iPhone: a horizontal rail of fixed cards.
        case scrolling(cardWidth: CGFloat)
        /// iPad: one row of equal columns.
        case row(columns: Int)
    }

    let arrangement: Arrangement
    /// The screen's side margin; rails scroll under it, headers align to it.
    let margin: CGFloat
    let rail: Rail

    static let minimumWideWidth: CGFloat = 720
    static let statsWidth: CGFloat = 380
    static let phoneCardWidth: CGFloat = 150
    static let rowCardMinimum: CGFloat = 150
    static let rowGap: CGFloat = 18
    static let maximumRowColumns = 5

    var isWide: Bool {
        if case .wide = arrangement { true } else { false }
    }

    static func forWidth(_ width: CGFloat) -> HomeLayout {
        let margin: CGFloat = width >= 700 ? 32 : 20
        guard width >= minimumWideWidth else {
            return HomeLayout(arrangement: .column, margin: margin, rail: .scrolling(cardWidth: phoneCardWidth))
        }
        let content = width - margin * 2
        let columns = min(maximumRowColumns, max(1, Int((content + rowGap) / (rowCardMinimum + rowGap))))
        return HomeLayout(arrangement: .wide(statsWidth: statsWidth), margin: margin, rail: .row(columns: columns))
    }
}

/// One section of Home. The order is the shared `homeBlocks` rule; this is its native projection.
enum HomeBlock: Hashable {
    case offline, firstSyncStrip, finished, nothingInProgress, welcome, firstSync, continueListeningFailed
    case slotBesideStats, everythingInProgress, stats, shelves, statsBesideShelves
}
