import Foundation
@preconcurrency import Shared

/// One chapter as the list surfaces consume it — a native Swift value type, so SwiftUI's diff
/// never re-reads a Swift-Export-bridged Kotlin `Chapter` across the boundary (rule 8).
/// Built once per book load; the bridged `[Chapter]` stays on the coordinator, feeding `ChapterMath` only.
struct ChapterRowModel: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let startMs: Int64
    let durationMs: Int64
}

/// The chapter READ surface — everything the player UI, CarPlay and the seeking sheet derive from
/// the memoized `chapterIndex` and the native `chapterRows`. Split out of `PlayerCoordinator.swift`
/// (the `+NowPlaying` precedent) to keep that file inside the 800-line cap; the stored properties
/// and `refreshChapterIndex()` stay there because `@Observable` storage cannot live in an extension.
@MainActor
extension PlayerCoordinator {
    var totalChapters: Int { chapterRows.count }

    var chapterTitle: String? { chapterRows.indices.contains(chapterIndex) ? chapterRows[chapterIndex].title : nil }

    var chapterPositionMs: Int64 {
        guard chapterRows.indices.contains(chapterIndex) else { return 0 }
        return max(0, bookPositionMs - chapterRows[chapterIndex].startMs)
    }

    var chapterDurationMs: Int64 {
        guard chapterRows.indices.contains(chapterIndex) else { return 0 }
        return chapterRows[chapterIndex].durationMs
    }

    func chapterTitleForIndex(_ index: Int) -> String? {
        chapterRows.indices.contains(index) ? chapterRows[index].title : nil
    }

    /// Chapter info for the seeking UI — rebuilt from the Swift-side chapter math.
    var currentChapterInfoForSeeking: PlaybackManagerChapterInfo? {
        guard chapterRows.indices.contains(chapterIndex) else { return nil }
        let index = chapterIndex
        let chapter = chapterRows[index]
        let endMs = chapter.startMs + chapter.durationMs
        return PlaybackManagerChapterInfo(
            index: Int32(index),
            title: chapter.title,
            startMs: chapter.startMs,
            endMs: endMs,
            remainingMs: max(0, endMs - bookPositionMs),
            totalChapters: Int32(chapterRows.count),
            isGenericTitle: false
        )
    }

    /// Jump to a chapter by index.
    func selectChapter(index: Int) {
        guard chapterRows.indices.contains(index) else { return }
        seekTo(positionMs: chapterRows[index].startMs)
    }
}
