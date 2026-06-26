import SwiftUI
import Shared

/// The inline "Up Next" chapters panel shown beside the player at regular width
/// (iPad / large iPhone landscape). It replaces the chapter *sheet* on those
/// devices — the chapter list is always visible as a second pane instead of a
/// modal. A "Chapters" heading, a "{N} chapters · {total length}" summary, and a
/// scrolling list of `ChapterRow` over the live chapter list; the current chapter
/// is tint-highlighted and tapping any row seeks to it.
struct NowPlayingUpNextPanel: View {
    let observer: PlayerCoordinator
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(String(localized: "player.chapters"))
                .font(.title2.bold())
                .foregroundStyle(.primary)

            Text(summary)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
                .padding(.bottom, 10)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(0..<observer.totalChapters, id: \.self) { index in
                        ChapterRow(
                            index: index,
                            title: observer.chapterTitleForIndex(index) ?? "Chapter \(index + 1)",
                            durationMs: index < observer.chapters.count ? observer.chapters[index].duration : 0,
                            isCurrent: index == observer.chapterIndex,
                            isPlaying: observer.isPlaying,
                            tint: tint,
                            onTap: { observer.selectChapter(index: index) }
                        )
                        .padding(.vertical, 6)
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .padding(.horizontal, 28)
        .padding(.vertical, 36)
        .frame(width: 360)
        .background(Color(.systemBackground).opacity(0.78))
        .overlay(alignment: .leading) {
            Divider().ignoresSafeArea()
        }
    }

    /// "{N} chapters · {total length}" — the total is the whole-book duration in
    /// compact long form (e.g. "33h 50m"), matching the design.
    private var summary: String {
        String(
            format: String(localized: "player.chapters_count_summary"),
            observer.totalChapters,
            Self.formatLongDuration(observer.bookDurationMs)
        )
    }

    /// Compact human-readable duration: "33h 50m", "50m", or "45s" — used for the
    /// at-a-glance total, distinct from the scrubber's exact "h:mm:ss".
    static func formatLongDuration(_ ms: Int64) -> String {
        let totalSeconds = max(0, ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        if hours > 0 {
            return "\(hours)h \(minutes)m"
        }
        if minutes > 0 {
            return "\(minutes)m"
        }
        return "\(totalSeconds)s"
    }
}
