import SwiftUI

/// An atmospheric, blurred copy of the book's cover that bleeds out the top of the
/// Book Detail screen and melts into the system background as it descends.
///
/// It gives the screen the book's colour personality as *ambient* — not by recolouring
/// text and controls (the per-book tint experiment read as "desaturated, like something
/// is wrong"), but by floating the hero over a soft, scaled-up wash of the cover art.
///
/// The same `BookCoverImage` (Nuke-backed, BlurHash placeholder) is reused as the source,
/// scaled up and heavily blurred, then faded with a vertical gradient mask so it dissolves
/// into `Color(.systemBackground)`. A `colorScheme`-aware scrim keeps the title and hero
/// perfectly legible on top.
///
/// **Reduce Transparency:** the blurred art is dropped for a flat, very subtle background —
/// the screen stays calm and the atmosphere never competes with the content.
///
/// Knobs (best fine-tuned in the simulator): `blurRadius`, `height`, and the mask's
/// gradient stops below.
struct CoverBackdrop: View {
    let bookId: String?
    let coverPath: String?
    let blurHash: String?

    /// How far the backdrop reaches down the screen before it has fully faded out.
    var height: CGFloat = 460
    /// Blur applied to the scaled-up cover. Higher = softer, more ambient.
    var blurRadius: CGFloat = 60

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        Group {
            if reduceTransparency {
                // Calm, flat fallback — no blurred art competing with the content.
                Color(.systemBackground)
            } else {
                blurredArt
            }
        }
        .frame(height: height)
        .frame(maxWidth: .infinity, alignment: .top)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var blurredArt: some View {
        BookCoverImage(bookId: bookId, coverPath: coverPath, blurHash: blurHash)
            .scaledToFill()
            // Scale up so the blurred art over-fills the band and no hard cover edges show.
            .scaleEffect(1.6)
            .blur(radius: blurRadius, opaque: true)
            // Soften the saturated wash a touch and lift legibility for the hero on top.
            .overlay(scrim)
            // Dissolve the whole band into the system background top-to-bottom.
            .mask(fadeMask)
            .clipped()
    }

    /// A scheme-aware scrim: a gentle base wash plus a stronger lift at the very top
    /// (under the nav bar / title) so chrome and the title stay legible.
    private var scrim: some View {
        let base = Color(.systemBackground)
        return LinearGradient(
            colors: [
                base.opacity(scheme == .dark ? 0.45 : 0.40),
                base.opacity(scheme == .dark ? 0.30 : 0.22)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    /// Vertical opacity ramp: solid at the top, fully transparent by the bottom — so the
    /// art melts into the background rather than ending on a hard edge.
    private var fadeMask: LinearGradient {
        LinearGradient(
            stops: [
                .init(color: .black, location: 0.0),
                .init(color: .black.opacity(0.85), location: 0.35),
                .init(color: .black.opacity(0.35), location: 0.7),
                .init(color: .clear, location: 1.0)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

// MARK: - Preview

#Preview("Cover backdrop") {
    ScrollView {
        ZStack(alignment: .top) {
            CoverBackdrop(bookId: nil, coverPath: nil, blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj")
            VStack(spacing: 12) {
                Color.clear.frame(height: 40)
                BookCoverImage(coverPath: nil, blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj")
                    .frame(width: 160, height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                Text("A Game of Thrones").font(.title2.bold())
                Text("George R.R. Martin").font(.callout)
            }
            .padding(.top, 24)
        }
    }
    .background(Color(.systemBackground))
}
