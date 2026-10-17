import CoreGraphics

/// How Home spends the width it is given, and the order its sections stand in — pure, so the responsive contract
/// and spec §2.1's fixed order are tested rather than buried in view code (iosApp rule 12).
///
/// A window narrower than `minimumWideWidth` is one column with a scrolling rail. A wider one (an iPad with its
/// sidebar is ~860pt of content) sets the stats card first at 380pt beside the shelves, or beside Resume when nothing
/// is loaded, and draws Also in Progress as one row of up to five columns that flow with the width (board Home-iPad).
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

    /// Home's sections, top to bottom: the slot when it shows, then Also in Progress → Your Last 7 Days → My Shelves.
    func blocks(slot: HomeSlotShape, hasAlsoInProgress: Bool) -> [HomeBlock] {
        var blocks: [HomeBlock] = []
        switch (slot, isWide) {
        case (.resume, true): blocks.append(.resumeBesideStats)
        case (.resume, false): blocks.append(.resume)
        case (.nothingToResume, _): blocks.append(.nothingToResume)
        case (.empty, _): break
        }
        if hasAlsoInProgress { blocks.append(.alsoInProgress) }
        switch (slot, isWide) {
        case (.resume, true): blocks.append(.shelves)
        case (_, true): blocks.append(.statsBesideShelves)
        case (_, false): blocks.append(contentsOf: [.stats, .shelves])
        }
        return blocks
    }
}

/// What the slot needs from the layout.
/// (`empty`, not `none`: `.none` reads as `Optional.none` in every comparison, as PR 1 found with `HomeSlot.None`.)
enum HomeSlotShape: Equatable { case empty, resume, nothingToResume }

/// One section of Home.
enum HomeBlock: Hashable {
    case resume, resumeBesideStats, nothingToResume, alsoInProgress, stats, shelves, statsBesideShelves
}

extension HomeSlotItem {
    /// Open question 5 (the window between Home PR 3 and PR 4), in one place: until PR 4 draws them, JustFinished
    /// and FirstSync take no space, and NothingInProgress / NewListener show the interim empty state.
    var shape: HomeSlotShape {
        switch self {
        case .resume: .resume
        case .nothingInProgress, .newListener: .nothingToResume
        case .absent, .firstSync, .justFinished: .empty
        }
    }
}
