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
        album: String = "",
        bookPositionMs: Int64,
        chapters: [Chapter],
        bookDurationMs: Int64,
        rate: Double,
        defaultRate: Double = 1.0,
        artworkPath: String?
    ) -> NowPlayingInfo {
        let window = ChapterMath.window(
            forPositionMs: bookPositionMs,
            in: chapters,
            bookDurationMs: bookDurationMs
        )
        var info = NowPlayingInfo(
            title: title,
            artist: artist,
            windowDurationMs: window.durationMs,
            windowElapsedMs: ChapterMath.windowPosition(forBookPositionMs: bookPositionMs, in: window),
            rate: rate,
            artworkPath: artworkPath,
            chapterNumber: window.index >= 0 ? window.index + 1 : nil,
            chapterCount: window.index >= 0 ? window.chapterCount : nil,
            defaultRate: defaultRate
        )
        info.album = album
        info.hasNextChapter = ChapterNavigation.shared.hasNext(positionMs: bookPositionMs, chapters: chapters)
        info.hasPreviousChapter = ChapterNavigation.shared.hasPrevious(chapters: chapters)
        return info
    }

    /// What the Lock Screen calls the chapter (spec §5.7): the shared `ChapterNaming` rule, in the mini player's
    /// words — the book's own title, "Chapter N" when it has none, "Whole book" for a chapterless book.
    static func chapterName(title: String?, number: Int, isWholeBook: Bool) -> String {
        switch ChapterNaming.shared.of(title: title, number: Int32(number), isWholeBook: isWholeBook).sealedType() {
        case .titled(let titled): titled.value.title
        case .untitled(let untitled):
            String(format: String(localized: "player.mini_chapter"), untitled.value.number)
        case .wholeBook: String(localized: "player.mini_whole_book")
        }
    }
}

@MainActor
extension PlayerCoordinator {
    func updateNowPlaying() {
        // Nothing held (nothing loaded, or a failed load) has no now-playing content — clear the
        // lock-screen controls; the retry lives in the app.
        guard engineBook != nil else {
            system.clear()
            return
        }
        // Title = the book, artist = the chapter, album = the author (§5.7), as Android's card and the browser say it.
        system.update(.chapterScoped(
            title: bookTitle,
            artist: NowPlayingInfo.chapterName(
                title: chapterTitle, number: chapterIndex + 1, isWholeBook: chapterRows.isEmpty),
            album: authorName,
            bookPositionMs: bookPositionMs,
            chapters: chapters,
            bookDurationMs: bookDurationMs,
            // Report a live rate ONLY while audio is actually advancing (`.playing`) — not while
            // buffering. `MPNowPlayingInfoCenter` extrapolates the displayed elapsed time as
            // `elapsed + rate·wallclock`, so a non-zero rate during the (now much longer) pre-load
            // buffer would tick the lock-screen clock forward from the resume point and then snap
            // it back when real playback starts. Rate 0 while buffering keeps elapsed honest.
            rate: isPlaying ? Double(playbackSpeed) : 0,
            defaultRate: Double(playbackSpeed),
            artworkPath: coverPath
        ))
    }
}
