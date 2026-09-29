import Foundation

/// The playback speeds ListenUp offers, and the pure rules around them.
///
/// One catalogue feeds every surface that changes speed — the in-app speed menu, the lock
/// screen's `changePlaybackRateCommand`, and the CarPlay rate button — so they can never offer
/// different rates. Platform-neutral (no UIKit), part of the shared Swift player core.
enum PlaybackRates {
    /// Every rate a listener can pick, slowest first.
    static let catalogue: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]

    /// The speed menu's entries: the catalogue, plus `current` in order when it is off-catalogue
    /// (set by another client or an older build) so the menu always has a checked entry.
    static func options(including current: Float) -> [Float] {
        guard !catalogue.contains(where: { isSame($0, current) }) else { return catalogue }
        return (catalogue + [current]).sorted()
    }

    /// The next faster catalogue rate, wrapping from the fastest back to the slowest — what the
    /// CarPlay rate button steps through on each tap.
    static func next(after current: Float) -> Float {
        catalogue.first { $0 > current && !isSame($0, current) } ?? catalogue[0]
    }

    /// A remote rate request (lock screen, CarPlay, Siri) made safe to apply: clamped into the
    /// catalogue's range, or `nil` for a non-positive rate — zero is a pause, not a speed, and the
    /// command forbids negatives.
    static func accepted(_ requested: Float) -> Float? {
        guard requested > 0, let slowest = catalogue.first, let fastest = catalogue.last else { return nil }
        return min(max(requested, slowest), fastest)
    }

    /// "1×", "1.25×", "0.5×" — trailing zeros trimmed, with the multiplication sign.
    static func format(_ speed: Float) -> String {
        let rounded = (speed * 100).rounded() / 100
        if rounded == rounded.rounded() {
            return "\(Int(rounded))×"
        }
        var text = String(format: "%.2f", rounded)
        while text.hasSuffix("0") { text.removeLast() }
        return "\(text)×"
    }

    private static func isSame(_ lhs: Float, _ rhs: Float) -> Bool { abs(lhs - rhs) < 0.001 }
}
