import SwiftUI
import Shared

/// The ListenUp score's breakdown, one tap away from `BookRatingSection`'s headline: "Combined from
/// N sources" when `score` has more than one, one row per outside source in `breakdown` with its
/// share of the score ("Audible · 4.7 · 1k · 38%"), a "Your listeners" row when they are part of it
/// ("Your listeners · 4.0 · 3 · 20%"), plus a "Refresh ratings" action when `canRefresh` (admin or
/// root). Each row's average is on the source's own curve, not ListenUp's.
/// `isRefreshingExternal` is the view model's own in-flight flag — true from the moment the RPC is
/// sent until the server answers, whether or not any score changed — so the button's busy/disabled
/// state binds to it directly rather than being guessed at locally: a refresh that finds nothing
/// new still re-enables the button.
struct RatingBreakdownSheet: View {
    let breakdown: [ExternalRatingRow]
    /// The ListenUp score the rows add up to; nil shows the rows without shares.
    var score: ExternalScore?
    /// Your listeners' average, shown as a row when `score` draws on it.
    var listeners: ListenersAverage?
    let canRefresh: Bool
    let isRefreshingExternal: Bool
    let onRefresh: () -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(rowTexts.enumerated()), id: \.offset) { _, row in
                        Text(row)
                    }
                } header: {
                    if let combinedFrom = Self.combinedFrom(score) {
                        Text(combinedFrom)
                            .textCase(nil)
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

    private var rowTexts: [String] {
        Self.rows(breakdown: breakdown, score: score, listeners: listeners)
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

    /// Every row the sheet lists: each outside source in `breakdown`, then "Your listeners" when
    /// `score` counts them. The row builders are `nonisolated` so tests can call them off the main
    /// actor — a main-actor closure inside `map` traps when a test calls it from elsewhere.
    nonisolated static func rows(
        breakdown: [ExternalRatingRow],
        score: ExternalScore?,
        listeners: ListenersAverage?
    ) -> [String] {
        let outside = breakdown.map { sourceRow($0, share: score?.outsideShares[$0.source]) }
        guard let listeners, let share = score?.listenersShare else { return outside }
        let listenersRow = row(
            label: String(localized: "rating.source_listeners"),
            average: listeners.averageHalfStars / 2,
            count: listeners.count,
            share: share
        )
        return outside + [listenersRow]
    }

    /// "Combined from 3 sources" — only when the score draws on more than one.
    nonisolated static func combinedFrom(_ score: ExternalScore?) -> String? {
        guard let score, score.sourceCount >= 2 else { return nil }
        return String(format: String(localized: "book.detail_rating_combined_from"), "\(score.sourceCount)")
    }

    /// "Audible · 4.5 · 8.1k", or with its share of the score, "Audible · 4.5 · 8.1k · 38%".
    nonisolated static func sourceRow(_ rating: ExternalRatingRow, share: Double? = nil) -> String {
        row(label: rating.source.displayName, average: rating.average, count: rating.count, share: share)
    }

    private nonisolated static func row(label: String, average: Double, count: Int, share: Double?) -> String {
        let averageText = RatingLabels.shared.averageLabel(average: average)
        let countText = RatingLabels.shared.compactCount(count: Int32(count))
        guard let share else {
            return String(
                format: String(localized: "book.detail_rating_source_row"),
                label,
                "\(averageText) · \(countText)"
            )
        }
        let percent = "\(Int((share * 100).rounded()))"
        return String(
            format: escapingLiteralPercents(String(localized: "book.detail_rating_source_row_share")),
            label,
            averageText,
            countText,
            percent
        )
    }

    /// The share template ends in a literal percent sign ("%4$@%"), which `String(format:)` would
    /// swallow as an unfinished specifier; double every percent sign that starts no specifier.
    private nonisolated static func escapingLiteralPercents(_ template: String) -> String {
        template.replacing(/%%|%(?:\d+\$)?[@d]|%/) { match in
            match.output == "%" ? "%%" : String(match.output)
        }
    }
}

// MARK: - Preview

#Preview("RatingBreakdownSheet") {
    RatingBreakdownSheet(
        breakdown: [
            ExternalRatingRow(source: .audible, average: 4.5, count: 8_100),
            ExternalRatingRow(source: .goodreads, average: 4.1, count: 620)
        ],
        score: ExternalScore(
            average: 4.3,
            count: 8_723,
            outsideShares: [.audible: 0.5, .goodreads: 0.3],
            listenersShare: 0.2
        ),
        listeners: ListenersAverage(averageHalfStars: 8, count: 3),
        canRefresh: true,
        isRefreshingExternal: false,
        onRefresh: {},
        onClose: {}
    )
}
