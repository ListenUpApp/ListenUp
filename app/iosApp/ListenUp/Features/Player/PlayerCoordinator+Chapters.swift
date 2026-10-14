import Foundation

/// The chapter the lock screen titles itself with. Split out of `PlayerCoordinator.swift` (the
/// `+NowPlaying` precedent); the stored chapter state stays there because `@Observable` storage
/// cannot live in an extension. Every in-app chapter surface reads `PlayerObserver` instead.
@MainActor
extension PlayerCoordinator {
    var chapterTitle: String? { chapterRows.indices.contains(chapterIndex) ? chapterRows[chapterIndex].title : nil }
}
