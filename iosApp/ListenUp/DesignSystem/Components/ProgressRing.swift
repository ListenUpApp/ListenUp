import SwiftUI

/// Circular progress ring (clean-coral): a `luFill` track under a coral arc, with an
/// optional centered play glyph. Used for compact series/book progress. `progress` is
/// clamped to `0...1`.
struct ProgressRing: View {
    let progress: Double
    var size: CGFloat = 34
    var lineWidth: CGFloat = 3
    var showGlyph: Bool = false

    private var clamped: Double { min(max(progress, 0), 1) }

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.luFill, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: clamped)
                .stroke(Color.luTint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
            if showGlyph {
                Image(systemName: "play.fill")
                    .font(.system(size: size * 0.32))
                    .foregroundStyle(Color.luTint)
            }
        }
        .frame(width: size, height: size)
        // Implicit animations honor Reduce Motion automatically; the arc eases on change.
        .animation(.easeOut(duration: 0.3), value: clamped)
        .accessibilityElement()
        .accessibilityLabel(Text("Progress"))
        .accessibilityValue(Text(clamped, format: .percent.precision(.fractionLength(0))))
    }
}

// MARK: - Preview

#Preview("ProgressRing") {
    HStack(spacing: 28) {
        ProgressRing(progress: 0.0)
        ProgressRing(progress: 0.4)
        ProgressRing(progress: 1.0)
        ProgressRing(progress: 0.65, showGlyph: true)
        ProgressRing(progress: 0.65, size: 56, lineWidth: 4, showGlyph: true)
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
