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
    /// The minimum gaps above and below a stacked cover.
    static let stackedCoverSpacing: CGFloat = 32
    /// Share of the width a compact-height cover may take.
    static let compactHeightCoverWidthShare: CGFloat = 0.4
    /// The cover's size limits: legible at the floor, never a billboard at the ceiling.
    static let coverRange: ClosedRange<CGFloat> = 120...460
    /// The cover inside a scrolling column, where height is no longer the constraint.
    static let preferredScrollingCoverSide: CGFloat = 240

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

    /// The stacked cover: as wide as the column allows, but only as tall as the height the
    /// controls leave over — so the whole player fits without scrolling wherever it can. `nil` when
    /// even the smallest cover would not fit: the column then scrolls instead (small phones,
    /// accessibility text sizes).
    ///
    /// - Parameters:
    ///   - columnWidth: the column's width.
    ///   - availableHeight: the height below the header.
    ///   - controlsHeight: the measured natural height of titles, scrubber, transport and the rest.
    static func stackedCoverSide(columnWidth: CGFloat, availableHeight: CGFloat, controlsHeight: CGFloat) -> CGFloat? {
        let fitted = min(
            columnWidth - 2 * horizontalMargin,
            availableHeight - controlsHeight - stackedCoverSpacing,
            coverRange.upperBound
        )
        return fitted >= coverRange.lowerBound ? fitted : nil
    }

    /// The compact-height cover, beside the controls: as tall as the space below the header allows,
    /// and never more than a share of the width, so the controls keep room.
    static func compactHeightCoverSide(in size: CGSize) -> CGFloat {
        let fitted = min(size.height - 2 * verticalMargin, size.width * compactHeightCoverWidthShare)
        return min(max(fitted, coverRange.lowerBound), coverRange.upperBound)
    }

    /// The scrolling fallback's cover — a fixed, comfortable size, narrowed to fit the column.
    static func scrollingCoverSide(columnWidth: CGFloat) -> CGFloat {
        max(coverRange.lowerBound, min(preferredScrollingCoverSide, columnWidth - 2 * horizontalMargin))
    }
}
