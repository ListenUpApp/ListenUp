import SwiftUI
import Shared

/// The per-source outside-rating breakdown, one tap away from `BookRatingSection`'s headline: one
/// row per source in `breakdown` ("Audible · 4.5 · 8.1k"), plus a "Refresh ratings" action when
/// `canRefresh` (admin or root). `isRefreshingExternal` is the view model's own in-flight flag —
/// true from the moment the RPC is sent until the server answers, whether or not any score
/// changed — so the button's busy/disabled state binds to it directly rather than being guessed
/// at locally: a refresh that finds nothing new still re-enables the button.
struct RatingBreakdownSheet: View {
    let breakdown: [ExternalRatingRow]
    let canRefresh: Bool
    let isRefreshingExternal: Bool
    let onRefresh: () -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(breakdown.enumerated()), id: \.offset) { _, rating in
                        Text(Self.sourceRow(rating))
                    }
                }

                if canRefresh {
                    Section {
                        refreshButton
                    }
                }
            }
            .navigationTitle(String(localized: "book.detail_rating_sources_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done")) { onClose() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var refreshButton: some View {
        Button(action: onRefresh) {
            HStack(spacing: 8) {
                if isRefreshingExternal {
                    ProgressView()
                } else {
                    Image(systemName: "arrow.clockwise")
                }
                Text(String(localized: "book.detail_rating_refresh"))
            }
            .frame(maxWidth: .infinity)
        }
        .disabled(isRefreshingExternal)
    }

    /// "Audible · 4.5 · 8.1k".
    static func sourceRow(_ rating: ExternalRatingRow) -> String {
        let detail = "\(RatingLabels.shared.averageLabel(average: rating.average))" +
            " · \(RatingLabels.shared.compactCount(count: Int32(rating.count)))"
        return String(format: String(localized: "book.detail_rating_source_row"), rating.source.displayName, detail)
    }
}

// MARK: - Preview

#Preview("RatingBreakdownSheet") {
    RatingBreakdownSheet(
        breakdown: [
            ExternalRatingRow(source: .audible, average: 4.5, count: 8_100),
            ExternalRatingRow(source: .goodreads, average: 4.1, count: 620),
        ],
        canRefresh: true,
        isRefreshingExternal: false,
        onRefresh: {},
        onClose: {}
    )
}
