import SwiftUI

/// A soft, contained halo of the book cover's own colours, placed *directly behind* the
/// hero cover image so the art reads as a gentle coloured glow (think Apple Music's
/// now-playing artwork glow) — not a full-bleed band across the top of the screen.
///
/// The same `BookCoverImage` (Nuke-backed) is reused as the source,
/// scaled up slightly and blurred so its colours bloom just past the cover's edges, then
/// nudged downward so the glow pools beneath the crisp cover that sits on top.
///
/// **Bounded by design:** the glow is sized to roughly the cover's footprint and clipped,
/// so its `scaledToFill` intrinsic size can never leak into the layout (an unbounded cover
/// once stretched Book Detail past the iPhone width). It must be given a concrete `size`.
///
/// **Reduce Transparency:** the blurred art is dropped entirely — no glow, just the crisp
/// cover on the standard background.
struct CoverGlow: View {
    let bookId: String?
    let coverPath: String?
    /// Content hash of the current cover, forwarded to `BookCoverImage` so the glow content-addresses
    /// the fresh cover after a re-scrape instead of blooming the stale local file's colours.
    var coverHash: String?

    /// The hero cover's edge length; the glow is framed to this so it stays bounded.
    let size: CGFloat

    /// How far past the cover the glow blooms. >1 lets colour spill beyond the edges.
    var scale: CGFloat = 1.2
    /// Blur applied to the scaled-up cover. Higher = softer, more diffuse.
    var blurRadius: CGFloat = 30
    /// Overall strength of the halo.
    var opacity: CGFloat = 0.6
    /// Downward nudge so the glow pools beneath the cover rather than ringing it evenly.
    var verticalOffset: CGFloat = 16

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        if reduceTransparency {
            // Calm fallback — no blurred art, the cover stands alone.
            Color.clear
                .frame(width: size, height: size)
        } else {
            // A fixed `Color.clear` sets the bounds (exactly size × size); the cover rides as a
            // clipped overlay so its `scaledToFill` intrinsic size can never leak into the layout.
            Color.clear
                .frame(width: size, height: size)
                .overlay {
                    BookCoverImage(bookId: bookId, coverPath: coverPath, coverHash: coverHash)
                        .scaledToFill()
                        .scaleEffect(scale)
                        .blur(radius: blurRadius, opaque: false)
                        .offset(y: verticalOffset)
                        .opacity(opacity)
                }
                .clipped()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

// MARK: - Preview

#Preview("Cover glow") {
    ZStack {
        Color(.systemBackground).ignoresSafeArea()
        ZStack {
            CoverGlow(
                bookId: nil,
                coverPath: nil,
                size: 196
            )
            BookCoverImage(coverPath: nil)
                .frame(width: 196, height: 196)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: .black.opacity(0.18), radius: 16, x: 0, y: 8)
        }
    }
}
