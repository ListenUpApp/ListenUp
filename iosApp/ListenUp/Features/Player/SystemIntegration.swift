import Foundation
import MediaPlayer
import os

/// Immutable snapshot of what the lock screen should show. The `Now Playing`
/// info center extrapolates the elapsed clock from `elapsedMs` + `rate`, so this
/// only needs pushing on discrete events (play/pause/seek/chapter/speed).
struct NowPlayingInfo: Equatable, Sendable {
    let title: String
    let artist: String
    /// Whole-book duration in milliseconds.
    let durationMs: Int64
    /// Whole-book elapsed position in milliseconds.
    let elapsedMs: Int64
    /// Playback rate — 0 when paused, the playback speed when playing.
    let rate: Double
    /// Filesystem path to the cover image, or `nil` when none is available.
    let artworkPath: String?
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
    /// Seek to a whole-book position in milliseconds.
    func remoteSeek(toMs positionMs: Int64)
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

    /// Begin routing remote commands to `handler`. Call once, after construction.
    func attach(handler: RemoteCommandHandler) {
        self.handler = handler
        configureRemoteCommands()
    }

    /// Push a fresh lock-screen snapshot.
    func update(_ info: NowPlayingInfo) {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = Self.dictionary(from: info)
    }

    /// Set the lock-screen skip intervals the system shows on its skip controls.
    /// Re-pushed whenever the user's skip-interval setting changes so the
    /// lock-screen glyphs reflect the chosen values.
    func updateSkipIntervals(forwardSeconds: Int, backwardSeconds: Int) {
        let center = MPRemoteCommandCenter.shared()
        center.skipForwardCommand.preferredIntervals = [NSNumber(value: forwardSeconds)]
        center.skipBackwardCommand.preferredIntervals = [NSNumber(value: backwardSeconds)]
    }

    /// Clear the lock screen (playback stopped / no book).
    func clear() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    /// Map a `NowPlayingInfo` to the `MPNowPlayingInfoCenter` dictionary. Pure,
    /// `nonisolated`, and `static` so it is testable without touching the live
    /// info center or the main actor.
    nonisolated static func dictionary(from info: NowPlayingInfo) -> [String: Any] {
        var dict: [String: Any] = [
            MPMediaItemPropertyTitle: info.title,
            MPMediaItemPropertyArtist: info.artist,
            MPMediaItemPropertyPlaybackDuration: Double(info.durationMs) / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(info.elapsedMs) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: info.rate
        ]
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
        let center = MPRemoteCommandCenter.shared()

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
            self?.handler?.remoteSeek(toMs: Int64(event.positionTime * 1000))
            return .success
        }
    }
}
