import SwiftUI

/// A large circular progress dial — a `luFill` track under a coral arc, with a soft radial glow
/// and a centered content slot — for hero progress moments (a long-running upload, an apply, a
/// scan). The iOS-language counterpart of the mockup's progress RING (never a wavy bar).
///
/// `progress` is the fill 0...1 when determinate, or nil for an indeterminate spinner (no fake
/// 0% before totals arrive — honest progress, per SOUL). Pass any center content (a percentage,
/// a counter) via the `content` slot. Generic and domain-free: reuse anywhere a hero ring fits.
struct CircularProgressDial<Content: View>: View {
    /// The fill 0...1, or nil for an indeterminate state.
    let progress: Double?
    var size: CGFloat = 172
    var lineWidth: CGFloat = 13
    @ViewBuilder var content: () -> Content

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var clamped: Double { min(max(progress ?? 0, 0), 1) }

    var body: some View {
        ZStack {
            // Soft radial glow behind the dial.
            Circle()
                .fill(
                    RadialGradient(
                        colors: [Color.luTint.opacity(0.22), .clear],
                        center: .center,
                        startRadius: 0,
                        endRadius: size * 0.37
                    )
                )
                .frame(width: size * 0.74, height: size * 0.74)

            Circle()
                .stroke(Color.luFill, lineWidth: lineWidth)

            if let progress {
                Circle()
                    .trim(from: 0, to: min(max(progress, 0), 1))
                    .stroke(Color.luTint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.35), value: clamped)
            } else {
                // Indeterminate — a coral arc that spins.
                IndeterminateArc(lineWidth: lineWidth)
            }

            content()
        }
        .frame(width: size, height: size)
        .accessibilityElement(children: .combine)
        .accessibilityValue(
            progress.map { Text($0, format: .percent.precision(.fractionLength(0))) }
                ?? Text(String(localized: "common.accessibility_loading"))
        )
    }
}

// MARK: - Indeterminate arc

/// A coral quarter-arc that rotates continuously, shown when totals aren't known yet. Static
/// (no spin) under Reduce Motion, so it still reads as "working" without animation.
private struct IndeterminateArc: View {
    let lineWidth: CGFloat
    @State private var spinning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.25)
            .stroke(Color.luTint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            .rotationEffect(.degrees(spinning ? 360 : 0))
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) {
                    spinning = true
                }
            }
    }
}

// MARK: - Preview

#Preview("CircularProgressDial") {
    VStack(spacing: 36) {
        CircularProgressDial(progress: 0.46) {
            VStack(spacing: 2) {
                Text("46%").font(.system(size: 34, weight: .bold).monospacedDigit())
                Text("3.6 of 7.8 MB").font(.footnote).foregroundStyle(Color.luLabel2)
            }
        }
        CircularProgressDial(progress: nil) {
            Text("…").font(.largeTitle).foregroundStyle(Color.luLabel2)
        }
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
