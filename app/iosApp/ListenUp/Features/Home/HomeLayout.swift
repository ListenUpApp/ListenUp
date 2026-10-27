import CoreGraphics

/// How Home spends the width it is given, and the order its sections stand in — pure, so the responsive contract
/// and spec §2.1's fixed order are tested rather than buried in view code (iosApp rule 12).
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

    /// Home's sections, top to bottom (spec §2.1, §3.4) — Android's `homeBlocks` twin. The offline pill sits under
    /// the greeting, then the library-arriving strip (H5), then the slot (or, when Continue Listening failed and there
    /// is no slot, the one-line failure in its place). Offline and the failure are separate signals, so both can show.
    /// Below it: Everything in Progress → Your Last 7 Days → My Shelves.
    /// A wide window puts the slot beside the stats, and the stats beside the shelves when there is no slot.
    func blocks(
        slot: HomeSlotShape,
        isOffline: Bool,
        continueListeningFailed: Bool,
        showsStrip: Bool,
        hasEverythingInProgress: Bool
    ) -> [HomeBlock] {
        var blocks: [HomeBlock] = []
        if isOffline { blocks.append(.offline) }
        if showsStrip { blocks.append(.firstSyncStrip) }
        if let slotBlock = slot.block {
            blocks.append(isWide ? .slotBesideStats : slotBlock)
        } else if continueListeningFailed {
            blocks.append(.continueListeningFailed)
        }
        if hasEverythingInProgress { blocks.append(.everythingInProgress) }
        if blocks.contains(.slotBesideStats) {
            blocks.append(.shelves)
        } else if isWide {
            blocks.append(.statsBesideShelves)
        } else {
            blocks.append(contentsOf: [.stats, .shelves])
        }
        return blocks
    }
}

/// What the slot needs from the layout.
/// (`empty`, not `none`: `.none` reads as `Optional.none` in every comparison, as PR 1 found with `HomeSlot.None`.)
enum HomeSlotShape: Equatable {
    case empty, finished, nothingInProgress, welcome, firstSync

    /// The block that draws this slot, or nil when there is none.
    var block: HomeBlock? {
        switch self {
        case .empty: nil
        case .finished: .finished
        case .nothingInProgress: .nothingInProgress
        case .welcome: .welcome
        case .firstSync: .firstSync
        }
    }
}

/// One section of Home.
enum HomeBlock: Hashable {
    case offline, firstSyncStrip, finished, nothingInProgress, welcome, firstSync, continueListeningFailed
    case slotBesideStats, everythingInProgress, stats, shelves, statsBesideShelves
}

extension HomeSlotItem {
    var shape: HomeSlotShape {
        switch self {
        case .absent: .empty
        case .justFinished: .finished
        case .nothingInProgress: .nothingInProgress
        case .newListener: .welcome
        case .firstSync: .firstSync
        }
    }
}
