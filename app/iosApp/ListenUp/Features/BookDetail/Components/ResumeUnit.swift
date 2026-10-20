import SwiftUI

/// Book Detail's progress unit (spec §2.9): the current chapter and the time left over a determinate progress bar
/// while in progress; the dates it was read between once finished; nothing before it starts. It sits directly above
/// the actions, so "where you are" reads before "what to do next".
struct ResumeUnit: View {
    let stage: BookReadingStage
    let currentChapter: BookCurrentChapter?
    let timeRemainingMs: Int64?
    let progress: Float?
    let finishedSpan: BookFinishedSpan?
    /// The hero's cover colours, when it has them: the bar draws the cover track under the harmonised fill.
    var cover: CoverColors?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        switch stage {
        case .notStarted:
            EmptyView()
        case .finished:
            // A finish with no recorded date still says Finished rather than leaving a gap.
            Text(finishedSpan.map(Self.finishedText) ?? String(localized: "book.detail_finished"))
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
        case .inProgress:
            inProgress
        }
    }

    private var inProgress: some View {
        let fraction = Double(min(max(progress ?? 0, 0), 1))
        return VStack(spacing: 6) {
            // Side by side at ordinary sizes; stacked at accessibility sizes, so the chapter is never squeezed to
            // "C…" by the time left (HIG, Typography: layouts adapt to every text size).
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    chapterLabel.lineLimit(3)
                    timeLeftLabel
                }
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    chapterLabel.lineLimit(1)
                    timeLeftLabel.layoutPriority(1)
                }
            }
            // HIG, Progress indicators: a determinate bar for a known amount. On a cover's colours, the cover track
            // under the harmonised fill (Covers boards, Q4); otherwise the system bar, as PR 2 shipped it.
            Group {
                if let cover {
                    ProgressView(value: fraction)
                        .progressViewStyle(CoverTrackProgressStyle(track: cover.track, fill: cover.action))
                } else {
                    ProgressView(value: fraction).tint(.listenUpOrange)
                }
            }
            .accessibilityLabel(String(format: String(localized: "book.detail_progress_a11y"),
                                       Int((fraction * 100).rounded())))
        }
        .accessibilityElement(children: .combine)
    }

    private var chapterLabel: some View {
        Text(currentChapter.map(Self.chapterText) ?? "")
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var timeLeftLabel: some View {
        if let timeRemainingMs {
            Text(Self.timeLeftText(remainingMs: timeRemainingMs))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    /// "Ch. 6 · Bridge Four".
    static func chapterText(_ chapter: BookCurrentChapter) -> String {
        String(format: String(localized: "book.detail_chapter_label"), chapter.number, chapter.title)
    }

    /// "40 hr, 31 min left" in the reader's locale.
    static func timeLeftText(remainingMs: Int64) -> String {
        DurationFormatting.timeLeft(ms: remainingMs)
    }

    /// "Finished · 12 Feb – 3 Mar 2026", or "Finished · 3 Mar 2026" with no recorded start.
    static func finishedText(_ span: BookFinishedSpan) -> String {
        let finished = date(ms: span.finishedAtMs).formatted(.dateTime.day().month(.abbreviated).year())
        guard let started = span.startedAtMs else {
            return String(format: String(localized: "book.detail_finished_on"), finished)
        }
        let start = date(ms: started).formatted(.dateTime.day().month(.abbreviated))
        return String(format: String(localized: "book.detail_finished_span"), start, finished)
    }

    private static func date(ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }
}

#Preview("Resume unit") {
    VStack(spacing: 32) {
        ResumeUnit(stage: .inProgress,
                   currentChapter: BookCurrentChapter(index: 5, number: 6, title: "Bridge Four", startMs: 0,
                                                      elapsedInChapterMs: 0),
                   timeRemainingMs: Int64((40 * 60 + 31) * 60_000), progress: 0.11, finishedSpan: nil)
        ResumeUnit(stage: .notStarted, currentChapter: nil, timeRemainingMs: nil, progress: nil, finishedSpan: nil)
    }
    .padding()
}
