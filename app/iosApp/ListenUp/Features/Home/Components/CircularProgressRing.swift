import SwiftUI

/// A circular progress ring — the signature affordance of the Continue-Listening section.
///
/// A faint track circle with a brand-tinted arc trimmed to `progress` (0...1), drawn
/// clockwise from the top. An optional centered percent label suits the larger hero card;
/// the compact row variant omits it.
///
/// Progress changes animate, gated under Reduce Motion. The ring is purely decorative —
/// the consuming row owns the `.accessibilityValue` so VoiceOver reads the percentage once.
struct CircularProgressRing: View {
    let progress: Double
    var lineWidth: CGFloat = 4
    var showPercentLabel: Bool = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var clampedProgress: Double {
        min(max(progress, 0), 1)
    }

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.listenUpOrange.opacity(0.18), lineWidth: lineWidth)

            Circle()
                .trim(from: 0, to: clampedProgress)
                .stroke(
                    Color.listenUpOrange,
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: clampedProgress)

            if showPercentLabel {
                Text("\(Int((clampedProgress * 100).rounded()))%")
                    .font(.caption2.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Preview

#Preview("Rings") {
    HStack(spacing: 24) {
        CircularProgressRing(progress: 0.25)
            .frame(width: 32, height: 32)

        CircularProgressRing(progress: 0.65)
            .frame(width: 32, height: 32)

        CircularProgressRing(progress: 0.72, lineWidth: 6, showPercentLabel: true)
            .frame(width: 64, height: 64)

        CircularProgressRing(progress: 1.0, lineWidth: 6, showPercentLabel: true)
            .frame(width: 64, height: 64)
    }
    .padding()
}
