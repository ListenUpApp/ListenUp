import CoreGraphics

/// How the full player arranges itself in the space it has.
///
/// Decided from the actual safe-area size (and whether text is at an accessibility size), never
/// from the device or the horizontal size class alone — HIG, Layout: "Determine layout based on
/// size classes, not device type or orientation", and rule 12's width-driven responsiveness. A Pro
/// Max in landscape reports a regular width yet has ~420pt of height, which is exactly where a
/// size-class fork went wrong.
enum PlayerLayoutMode: Equatable {
    /// Player column beside the always-visible "Up Next" chapter pane (full-size iPad).
    case regular
    /// Short and wide (a phone in landscape): the cover sits beside the controls.
    case compactHeight
    /// One column, cover above controls; scrolls when it cannot fit (phones, narrow Split View).
    case stacked

    /// Below this height a wide window puts the cover beside the controls.
    static let compactHeightThreshold: CGFloat = 500
    /// Width the chapter pane needs beside a usable player column.
    static let paneMinimumWidth: CGFloat = 768
    /// The pane needs more width when text is at an accessibility size (HIG, Typography: "Consider
    /// adjusting your layout at large font sizes").
    static let paneMinimumWidthAtAccessibilitySizes: CGFloat = 1000
    /// Height the two-pane layout needs.
    static let paneMinimumHeight: CGFloat = 600

    /// Side margins around the player column's content.
    static let horizontalMargin: CGFloat = 26
    /// Top/bottom breathing room around the cover in compact height.
    static let verticalMargin: CGFloat = 16
    /// Share of the height a stacked cover may take, leaving room for titles and transport.
    static let stackedCoverHeightShare: CGFloat = 0.42
    /// Share of the width a compact-height cover may take.
    static let compactHeightCoverWidthShare: CGFloat = 0.4
    /// The cover's size limits: legible at the floor, never a billboard at the ceiling.
    static let coverRange: ClosedRange<CGFloat> = 120...460

    static func resolve(size: CGSize, isAccessibilitySize: Bool) -> PlayerLayoutMode {
        if size.height < compactHeightThreshold, size.width > size.height {
            return .compactHeight
        }
        let paneWidth = isAccessibilitySize ? paneMinimumWidthAtAccessibilitySizes : paneMinimumWidth
        if size.width >= paneWidth, size.height >= paneMinimumHeight {
            return .regular
        }
        return .stacked
    }

    /// The cover's side length for the column (or, in compact height, the window) it lives in.
    static func coverSide(in size: CGSize, mode: PlayerLayoutMode) -> CGFloat {
        let fitted: CGFloat
        switch mode {
        case .compactHeight:
            fitted = min(size.height - 2 * verticalMargin, size.width * compactHeightCoverWidthShare)
        case .regular, .stacked:
            fitted = min(size.width - 2 * horizontalMargin, size.height * stackedCoverHeightShare)
        }
        return min(max(fitted, coverRange.lowerBound), coverRange.upperBound)
    }
}
