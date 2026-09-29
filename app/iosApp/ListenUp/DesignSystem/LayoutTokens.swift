import SwiftUI

/// Corner radii: one short scale instead of seventeen literal values.
///
/// Shapes keep `RoundedRectangle`'s default continuous style, the squircle the system draws. A
/// shape nested inside another rounded shape uses `.concentric()` (iOS 26's `ConcentricRectangle`)
/// against the parent's `.containerShape`, so its corners follow the parent's curve at whatever
/// inset it sits. Geometry-driven radii — a circle's `size / 2`, a tile's `size * 0.3`, a
/// one-point density tick — stay computed at their call sites, and the auth card keeps its own
/// `AuthMetrics.cardCornerRadius`.
enum Radius {
    /// 4pt — thumbnails, bars, swatches.
    static let xs: CGFloat = 4
    /// 8pt — covers in rows and grids, badges, small buttons.
    static let s: CGFloat = 8
    /// 12pt — inset panels, fields, chips, tiles.
    static let m: CGFloat = 12
    /// 16pt — cards and banners.
    static let l: CGFloat = 16
    /// 20pt — large cards, hero covers.
    static let xl: CGFloat = 20
    /// 24pt — hero containers.
    static let xxl: CGFloat = 24
}

/// Padding: a 4-point scale for insets and margins, so related content lines up across screens
/// (HIG, Layout: "Align elements to make them easier to scan").
///
/// Optical nudges below the scale (1–3pt), one-off breathing room above it (a 40pt empty-state
/// inset, a 100pt scroll clearance) and insets derived from geometry (a divider aligned past an
/// icon column) stay literal at their call sites.
enum Spacing {
    /// 4pt
    static let xxs: CGFloat = 4
    /// 8pt
    static let xs: CGFloat = 8
    /// 12pt
    static let s: CGFloat = 12
    /// 16pt — the standard content margin.
    static let m: CGFloat = 16
    /// 20pt
    static let l: CGFloat = 20
    /// 24pt
    static let xl: CGFloat = 24
    /// 32pt — the end of a scrolling page.
    static let xxl: CGFloat = 32
}

extension Shape where Self == ConcentricRectangle {
    /// A rounded rectangle nested inside a `.containerShape`: its corners follow the container's
    /// curve at whatever inset it sits (the container's radius less the gap), never tighter than
    /// `minimum`. SwiftUI, ConcentricRectangle: corners "concentric relative to a container
    /// shape's corners". The container must declare its shape with `.containerShape(_:)`.
    static func concentric(minimum: CGFloat = Radius.xs) -> Self {
        ConcentricRectangle(corners: .concentric(minimum: .fixed(minimum)), isUniform: true)
    }
}
