import Foundation
import QuartzCore

/// Pure interpolation math, split out so it is testable without a display link.
enum PositionInterpolator {
    /// Given the last real sample (`sampleMs` at `sampleTimestamp`, advancing at
    /// `rate` ms-of-audio per second-of-wall-time) and the current wall time,
    /// returns the interpolated position. A non-positive `rate` returns `sampleMs`
    /// unchanged (paused — no interpolation).
    static func interpolate(
        sampleMs: Int64,
        sampleTimestamp: CFTimeInterval,
        rate: Double,
        now: CFTimeInterval
    ) -> Int64 {
        guard rate > 0 else { return sampleMs }
        let elapsedSeconds = max(0, now - sampleTimestamp)
        return sampleMs + Int64(elapsedSeconds * rate * 1000)
    }
}

/// Floors a position to whole seconds so the UI's time labels (and the
/// mini-player) update ~1×/sec instead of at display-refresh rate.
enum PositionQuantizer {
    static func displayMs(_ positionMs: Int64) -> Int64 { (positionMs / 1_000) * 1_000 }
}

/// Smooths `AudioEngine`'s coarse position samples into a per-frame interpolated
/// position for the UI, driven by `CADisplayLink`. `NSObject` so it can host the
/// `@objc` display-link selector.
@MainActor
@Observable
final class PositionTracker: NSObject {
    /// Current interpolated whole-book position in milliseconds (per-frame).
    private(set) var positionMs: Int64 = 0

    /// `positionMs` floored to whole seconds. Mutates ~1×/sec, so views that only
    /// show seconds (time labels, mini-player) read this and re-evaluate ~1×/sec
    /// instead of at display-refresh rate.
    private(set) var displayPositionMs: Int64 = 0

    private var sampleMs: Int64 = 0
    private var sampleTimestamp: CFTimeInterval = 0
    private var rate: Double = 0
    private var displayLink: CADisplayLink?

    /// Feed a real position sample from the audio engine.
    func update(positionMs: Int64, rate: Double) {
        self.sampleMs = positionMs
        self.sampleTimestamp = CACurrentMediaTime()
        self.rate = rate
        self.positionMs = positionMs
        self.displayPositionMs = PositionQuantizer.displayMs(positionMs)
        if rate > 0 { startDisplayLink() } else { stopDisplayLink() }
    }

    /// Stop interpolation and zero the position (teardown, or a new book).
    func reset() {
        stopDisplayLink()
        sampleMs = 0
        sampleTimestamp = 0
        rate = 0
        positionMs = 0
        displayPositionMs = 0
    }

    private func startDisplayLink() {
        guard displayLink == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    /// `CADisplayLink` callback. `nonisolated` because the selector is invoked by
    /// the framework; it lands on the main run loop (the link is added to `.main`),
    /// so `MainActor.assumeIsolated` is sound.
    @objc private nonisolated func tick() {
        MainActor.assumeIsolated {
            let interpolated = PositionInterpolator.interpolate(
                sampleMs: sampleMs,
                sampleTimestamp: sampleTimestamp,
                rate: rate,
                now: CACurrentMediaTime()
            )
            positionMs = interpolated
            // Only publish the coarse value when the whole-second bucket changes, so
            // label/mini-player observers don't invalidate every frame.
            let bucket = PositionQuantizer.displayMs(interpolated)
            if bucket != displayPositionMs { displayPositionMs = bucket }
        }
    }
}
