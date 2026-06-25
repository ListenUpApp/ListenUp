import SwiftUI

/// Series-list progress affordance: a "Complete" pill, a "Not started" label, or an
/// "X of Y" linear bar — driven by `SeriesProgressState` (named to avoid colliding
/// with the Swift Export-bridged Kotlin `SeriesProgress`).
struct SeriesProgressBadge: View {
    let state: SeriesProgressState

    var body: some View {
        switch state {
        case .complete:
            HStack(spacing: 5) {
                Image(systemName: "checkmark").font(.caption2.weight(.bold))
                Text(String(localized: "series.complete"))
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.luTint)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Capsule().fill(Color.luTint.opacity(0.12)))
            .accessibilityLabel(Text(String(localized: "series.complete")))
        case .notStarted:
            Text(String(localized: "series.not_started"))
                .font(.footnote)
                .foregroundStyle(Color.luLabel3)
        case let .partial(finished, total):
            HStack(spacing: 10) {
                ProgressBar(progress: Float(state.fraction))
                    .frame(maxWidth: 230, maxHeight: 4)
                Text(verbatim: String(format: String(localized: "series.x_of_y"), finished, total))
                    .font(.caption).monospacedDigit()
                    .foregroundStyle(Color.luLabel2)
                    .fixedSize()
            }
        }
    }
}

#Preview("SeriesProgressBadge") {
    VStack(alignment: .leading, spacing: 18) {
        SeriesProgressBadge(state: .complete)
        SeriesProgressBadge(state: .notStarted)
        SeriesProgressBadge(state: .partial(finished: 3, total: 8))
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
