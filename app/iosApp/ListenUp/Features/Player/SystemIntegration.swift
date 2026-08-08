import Foundation
import MediaPlayer
import os

/// Immutable snapshot of what the lock screen should show. The `Now Playing`
/// info center extrapolates the elapsed clock from `windowElapsedMs` + `rate`, so this
/// only needs pushing on discrete events (play/pause/seek/chapter/speed).
///
/// Every position field here is WINDOW-relative — scoped to the current chapter (or the whole
/// book when chapterless). Nothing on this type carries a book-relative value.
struct NowPlayingInfo: Equatable, Sendable {
    let title: String
    let artist: String
    /// Duration of the presented WINDOW in milliseconds — the current chapter's length, or the
    /// whole book when the book has no chapters.
    ///
    /// Named for its coordinate space on purpose. This used to be `durationMs`, documented
    /// "whole-book"; changing what it carries while leaving the name unqualified is exactly how the
    /// Android side shipped #1251, where a book-relative value reached a chapter-relative surface.
    /// If you are passing a whole-book value into this field, you are at the wrong altitude.
    let windowDurationMs: Int64
    /// Elapsed position WITHIN the presented window in milliseconds. See [windowDurationMs].
    let windowElapsedMs: Int64
    /// Playback rate — 0 when paused, the playback speed when playing.
    let rate: Double
    /// Filesystem path to the cover image, or `nil` when none is available.
    let artworkPath: String?
    /// 1-based chapter number, or `nil` for a chapterless book (nothing to number).
    let chapterNumber: Int?
    /// Total chapter count, or `nil` for a chapterless book.
    let chapterCount: Int?
}

/// Remote-command intents `SystemIntegration` forwards to its handler. The
/// coordinator (Plan 2) conforms to this.
@MainActor
protocol RemoteCommandHandler: AnyObject {
    func remoteTogglePlayPause()
    func remotePlay()
    func remotePause()
    func remoteSkipForward()
    func remoteSkipBackward()
    /// Seek to a position within the CURRENTLY PRESENTED WINDOW (the current chapter, or the whole
    /// book when chapterless) in milliseconds — which is what `changePlaybackPositionCommand`
    /// reports once the info center is chapter-scoped. The handler owns translating it back to a
    /// book position; see `PlayerCoordinator.remoteSeek(toWindowPositionMs:)`.
    func remoteSeek(toWindowPositionMs positionMs: Int64)
}

/// Bridges player state to `MPNowPlayingInfoCenter` and routes
/// `MPRemoteCommandCenter` commands back to a `RemoteCommandHandler`.
@MainActor
final class SystemIntegration {
    /// Single-slot cache for the *current* Now Playing artwork, guarded by an unfair lock
    /// (iosApp concurrency rule: guard shared mutable state, no `nonisolated(unsafe)`).
    /// Now Playing shows one book at a time, so a single slot keeps memory bounded to one
    /// image and the cache deterministic (no `NSCache` pressure eviction).
    nonisolated private static let artworkCache =
        OSAllocatedUnfairLock<(path: String, artwork: MPMediaItemArtwork)?>(uncheckedState: nil)
    /// Lock-screen now-playing art is shown near screen-width; cap generously so it stays crisp.
    nonisolated private static let artworkMaxPixels = 1024

    private weak var handler: RemoteCommandHandler?

    /// Where now-playing snapshots are published. The session's center when a
    /// `MPNowPlayingSession` is in play, the process-global one otherwise.
    private let infoCenter: MPNowPlayingInfoCenter
    /// Where remote commands are registered — must pair with `infoCenter`: a session honors
    /// only its own command center.
    private let commandCenter: MPRemoteCommandCenter

    /// Create the bridge, optionally scoped to a `MPNowPlayingSession`.
    ///
    /// With a session (production): the system derives play/pause and timeline state from the
    /// `AVQueuePlayer` attached to it — the authoritative signal CarPlay and the lock screen
    /// render, rather than inferring it from audio-session activity. Without one (unit tests,
    /// previews): the legacy process-global centers.
    init(session: MPNowPlayingSession? = nil) {
        infoCenter = session?.nowPlayingInfoCenter ?? .default()
        commandCenter = session?.remoteCommandCenter ?? .shared()
    }

    /// Begin routing remote commands to `handler`. Call once, after construction.
    func attach(handler: RemoteCommandHandler) {
        self.handler = handler
        configureRemoteCommands()
    }

    /// Push a fresh lock-screen snapshot.
    func update(_ info: NowPlayingInfo) {
        infoCenter.nowPlayingInfo = Self.dictionary(from: info)
    }

    /// Set the lock-screen skip intervals the system shows on its skip controls.
    /// Re-pushed whenever the user's skip-interval setting changes so the
    /// lock-screen glyphs reflect the chosen values.
    func updateSkipIntervals(forwardSeconds: Int, backwardSeconds: Int) {
        commandCenter.skipForwardCommand.preferredIntervals = [NSNumber(value: forwardSeconds)]
        commandCenter.skipBackwardCommand.preferredIntervals = [NSNumber(value: backwardSeconds)]
    }

    /// Clear the lock screen (playback stopped / no book).
    func clear() {
        infoCenter.nowPlayingInfo = nil
    }

    /// Map a `NowPlayingInfo` to the `MPNowPlayingInfoCenter` dictionary. Pure,
    /// `nonisolated`, and `static` so it is testable without touching the live
    /// info center or the main actor.
    nonisolated static func dictionary(from info: NowPlayingInfo) -> [String: Any] {
        var dict: [String: Any] = [
            MPMediaItemPropertyTitle: info.title,
            MPMediaItemPropertyArtist: info.artist,
            MPMediaItemPropertyPlaybackDuration: Double(info.windowDurationMs) / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(info.windowElapsedMs) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: info.rate
        ]
        // Omitted rather than zeroed for a chapterless book: 0-of-0 renders as a real position.
        if let number = info.chapterNumber, let count = info.chapterCount {
            dict[MPNowPlayingInfoPropertyChapterNumber] = number
            dict[MPNowPlayingInfoPropertyChapterCount] = count
        }
        if let path = info.artworkPath, let artwork = artwork(forPath: path) {
            dict[MPMediaItemPropertyArtwork] = artwork
        }
        return dict
    }

    /// Resolve (and memoize) the downsampled lock-screen artwork for `path`.
    /// `nonisolated static` + `OSAllocatedUnfairLock`-guarded single slot, so `dictionary(from:)` stays pure-signature.
    nonisolated static func artwork(forPath path: String) -> MPMediaItemArtwork? {
        artworkCache.withLockUnchecked { cache in
            if let cache, cache.path == path { return cache.artwork }
            guard let image = ImageDownsampler.downsampledImage(atPath: path, maxPixelSize: artworkMaxPixels) else {
                return nil
            }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            cache = (path, artwork)
            return artwork
        }
    }

    private func configureRemoteCommands() {
        let center = commandCenter

        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.handler?.remoteTogglePlayPause()
            return .success
        }
        center.playCommand.addTarget { [weak self] _ in
            self?.handler?.remotePlay()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.handler?.remotePause()
            return .success
        }
        center.skipForwardCommand.addTarget { [weak self] _ in
            self?.handler?.remoteSkipForward()
            return .success
        }
        center.skipBackwardCommand.addTarget { [weak self] _ in
            self?.handler?.remoteSkipBackward()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self?.handler?.remoteSeek(toWindowPositionMs: Int64(event.positionTime * 1000))
            return .success
        }
    }
}
