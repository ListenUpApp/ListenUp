import Foundation
@preconcurrency import Shared

/// The playback speeds ListenUp offers, and the pure rules around them.
///
/// One catalogue feeds every surface that changes speed — the in-app speed menu, the lock
/// screen's `changePlaybackRateCommand`, and the CarPlay rate button — so they can never offer
/// different rates. Platform-neutral (no UIKit), part of the shared Swift player core.
enum PlaybackRates {
    /// Every rate a listener can pick, slowest first.
    static let catalogue: [Float] = PlaybackPresets.shared.SPEEDS

    /// The speed menu's entries: the catalogue, plus `current` in order when it is off-catalogue
    /// (set by another client or an older build) so the menu always has a checked entry.
    static func options(including current: Float) -> [Float] {
        PlaybackPresets.shared.speedChoicesIncluding(current: current)
    }

    /// The next faster catalogue rate, wrapping from the fastest back to the slowest — what the
    /// CarPlay rate button steps through on each tap.
    static func next(after current: Float) -> Float {
        PlaybackPresets.shared.nextSpeedInCycle(current: current)
    }

    /// A remote rate request (lock screen, CarPlay, Siri) made safe to apply: clamped into the
    /// catalogue's range, or `nil` for a non-positive rate — zero is a pause, not a speed, and the
    /// command forbids negatives.
    static func accepted(_ requested: Float) -> Float? {
        guard requested > 0, let slowest = catalogue.first, let fastest = catalogue.last else { return nil }
        return min(max(requested, slowest), fastest)
    }
}
