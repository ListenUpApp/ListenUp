import Foundation
@preconcurrency import Shared

extension NowPlayingInfo {
    /// Build the chapter-scoped lock-screen snapshot for a book position.
    ///
    /// The lock screen, Control Center and (later) the CarPlay now-playing template all present the
    /// CURRENT CHAPTER, not the whole book — the same way a music player shows the current track
    /// rather than the album. `ChapterMath.Window` is the single place that scoping is decided, and
    /// a chapterless book falls back to one whole-book window.
    ///
    /// Pure and coordinator-free so the window→snapshot mapping is unit-testable on its own, in
    /// keeping with the split already used by `ChapterMath` and the other player policies.
    static func chapterScoped(
        title: String,
        artist: String,
        bookPositionMs: Int64,
        chapters: [Chapter],
        bookDurationMs: Int64,
        rate: Double,
        artworkPath: String?
    ) -> NowPlayingInfo {
        let window = ChapterMath.window(
            forPositionMs: bookPositionMs,
            in: chapters,
            bookDurationMs: bookDurationMs
        )
        return NowPlayingInfo(
            title: title,
            artist: artist,
            windowDurationMs: window.durationMs,
            windowElapsedMs: ChapterMath.windowPosition(forBookPositionMs: bookPositionMs, in: window),
            rate: rate,
            artworkPath: artworkPath,
            chapterNumber: window.index >= 0 ? window.index + 1 : nil,
            chapterCount: window.index >= 0 ? window.chapterCount : nil
        )
    }
}

@MainActor
extension PlayerCoordinator {
    func updateNowPlaying() {
        // `.error` keeps the in-app player visible (for the inline retry) but has no now-playing
        // content — clear the lock-screen controls; the retry lives in the app.
        guard isVisible, !isErrored else {
            system.clear()
            return
        }
        system.update(.chapterScoped(
            title: chapterTitle ?? bookTitle,
            artist: authorName,
            bookPositionMs: bookPositionMs,
            chapters: chapters,
            bookDurationMs: bookDurationMs,
            // Report a live rate ONLY while audio is actually advancing (`.playing`) — not while
            // buffering. `MPNowPlayingInfoCenter` extrapolates the displayed elapsed time as
            // `elapsed + rate·wallclock`, so a non-zero rate during the (now much longer) pre-load
            // buffer would tick the lock-screen clock forward from the resume point and then snap
            // it back when real playback starts. Rate 0 while buffering keeps elapsed honest.
            rate: isPlaying ? Double(playbackSpeed) : 0,
            artworkPath: coverPath
        ))
    }
}
