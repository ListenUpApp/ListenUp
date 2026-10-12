import Foundation

/// One chapter as the list surfaces consume it — a native Swift value type, so SwiftUI's diff
/// never re-reads a Swift-Export-bridged Kotlin `Chapter` across the boundary (rule 8).
/// Built once per book load (the coordinator's from the prepared book, `PlayerObserver`'s from the shared rows).
struct ChapterRowModel: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let startMs: Int64
    let durationMs: Int64
}

/// The chapter the lock screen titles itself with. Split out of `PlayerCoordinator.swift` (the
/// `+NowPlaying` precedent); the stored chapter state stays there because `@Observable` storage
/// cannot live in an extension. Every in-app chapter surface reads `PlayerObserver` instead.
@MainActor
extension PlayerCoordinator {
    var chapterTitle: String? { chapterRows.indices.contains(chapterIndex) ? chapterRows[chapterIndex].title : nil }
}
