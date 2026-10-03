import SwiftUI
import Shared

/// The ListenUp score's sources, one tap away from `BookRatingSection`'s score row: "Combined from
/// N sources" when `score` has more than one, the score itself ("★ 4.6 · ListenUp score · 12k
/// ratings"), then one row per outside source in `breakdown` — its average, rating count, share of
/// the score as a number and a bar, and how fresh it is ("Updated 3 days ago") — and a "Your
/// listeners" row when they are part of it, plus a quiet "Refresh ratings" action when `canRefresh`
/// (admin or root). Each row's average is on the source's own curve, not ListenUp's.
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
    /// Now, in epoch ms, for each source's "Updated N days ago".
    let nowMs: Int64

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let score {
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Text("\u{2605} \(RatingLabels.shared.averageLabel(average: score.average))")
                                .font(.largeTitle.weight(.bold))
                            Text(String(
                                format: String(localized: "book.detail_rating_score_detail"),
                                String(localized: "book.detail_rating_score"),
                                BookRatingSection.countLabel(score.count)
                            ))
                            .foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                    }
                    ForEach(sourceRows, id: \.label) { sourceRow($0) }
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
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done")) { onClose() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var sourceRows: [SourceRowModel] {
        Self.rows(breakdown: breakdown, score: score, listeners: listeners, nowMs: nowMs)
    }

    private func sourceRow(_ row: SourceRowModel) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(row.label).fontWeight(.semibold)
                Spacer()
                Text("\u{2605} \(row.average)").fontWeight(.semibold)
            }
            HStack {
                Text(row.count)
                Spacer()
                if let shareLabel = row.shareLabel { Text(shareLabel) }
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
            if let share = row.share {
                ProgressView(value: share)
                    .tint(Color.luTint)
                    .accessibilityHidden(true) // the share is spoken as its percentage
            }
            if let updated = row.updated {
                Text(updated)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, Spacing.xxs)
        .accessibilityElement(children: .combine)
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
        listeners: ListenersAverage?,
        nowMs: Int64
    ) -> [SourceRowModel] {
        let outside = breakdown.map {
            row(
                label: $0.source.displayName,
                average: $0.average,
                count: $0.count,
                share: score?.outsideShares[$0.source],
                updated: updatedLabel(fetchedAtMs: $0.fetchedAtMs, nowMs: nowMs)
            )
        }
        guard let listeners, let share = score?.listenersShare else { return outside }
        return outside + [row(
            label: String(localized: "rating.source_listeners"),
            average: listeners.averageHalfStars / 2,
            count: listeners.count,
            share: share,
            updated: nil
        )]
    }

    /// "Combined from 3 sources" — only when the score draws on more than one.
    nonisolated static func combinedFrom(_ score: ExternalScore?) -> String? {
        guard let score, score.sourceCount >= 2 else { return nil }
        return String(format: String(localized: "book.detail_rating_combined_from"), "\(score.sourceCount)")
    }

    /// "Updated today", "Updated yesterday", "Updated 3 days ago"; nil when the server never said.
    nonisolated static func updatedLabel(fetchedAtMs: Int64?, nowMs: Int64) -> String? {
        guard let fetchedAtMs else { return nil }
        let days = Int(RatingLabels.shared.daysSince(fetchedAtMs: fetchedAtMs, nowMs: nowMs))
        switch days {
        case 0: return String(localized: "book.detail_rating_updated_today")
        case 1: return String(localized: "book.detail_rating_updated_yesterday")
        default: return String(format: String(localized: "book.detail_rating_updated_days"), days)
        }
    }

    private nonisolated static func row(
        label: String,
        average: Double,
        count: Int,
        share: Double?,
        updated: String?
    ) -> SourceRowModel {
        SourceRowModel(
            label: label,
            average: RatingLabels.shared.averageLabel(average: average),
            count: BookRatingSection.countLabel(count),
            share: share,
            shareLabel: share.map {
                String(format: String(localized: "book.detail_rating_share"), "\(Int(($0 * 100).rounded()))")
            },
            updated: updated
        )
    }
}

/// One row of the sources sheet, as native values.
struct SourceRowModel: Equatable {
    let label: String
    let average: String
    let count: String
    let share: Double?
    let shareLabel: String?
    let updated: String?
}

// MARK: - Preview

#Preview("RatingBreakdownSheet") {
    RatingBreakdownSheet(
        breakdown: [
            ExternalRatingRow(source: .audible, average: 4.5, count: 8_100, fetchedAtMs: nil),
            ExternalRatingRow(source: .goodreads, average: 4.1, count: 620, fetchedAtMs: nil)
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
        onClose: {},
        nowMs: 0
    )
}
