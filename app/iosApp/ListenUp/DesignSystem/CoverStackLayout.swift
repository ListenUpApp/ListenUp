import CoreGraphics

/// Pure layout math for `CoverStack`'s overlapping deck.
///
/// Extracted so the deck geometry — total width, per-layer horizontal offset, scale,
/// and z-order — is unit-testable without a running view tree. The view supplies the
/// front-cover `size` and the `peek` indent; these helpers place each layer.
/// (Mirrors the `PlayerGestureMath` pure-helper precedent.)
struct CoverStackLayout {
    /// Number of covers actually drawn (already clamped to the deck's max).
    let coverCount: Int
    /// Edge length of the front (largest, index 0) cover.
    let size: CGFloat
    /// Horizontal indent added for each deeper layer.
    let peek: CGFloat

    /// Total width the deck occupies: front cover plus one `peek` per extra layer.
    ///
    /// This is the *unscaled* bounding width — deeper layers are drawn slightly smaller
    /// (`scale(at:)`), so the painted deck sits a hair inside the trailing edge. Callers
    /// align against this width, not the last cover's scaled edge.
    var totalWidth: CGFloat {
        guard coverCount > 0 else { return size }
        return size + CGFloat(coverCount - 1) * peek
    }

    /// Leading x-offset of the cover at `index` (0 = front, drawn flush left).
    func xOffset(at index: Int) -> CGFloat { CGFloat(index) * peek }

    /// Scale of the cover at `index`: each deeper layer is 5% smaller.
    func scale(at index: Int) -> CGFloat { 1 - CGFloat(index) * 0.05 }

    /// z-index of the cover at `index`: the front cover sits on top.
    func zIndex(at index: Int) -> Double { Double(-index) }
}
