import AVFoundation

/// The engine's volume-boost gain stage: lifecycle for the per-book sample ring and loudness
/// meter feed, and the mix factory `rebuildQueue` attaches to every fresh `AVPlayerItem`.
///
/// Split out of `AudioEngine.swift` to keep that file inside the 550-line cap; the stored
/// properties stay there because Swift extensions cannot declare them.
extension AudioEngine {
    /// Begin measuring a new book. The ring and feed are per-book — the R128 meter integrates
    /// loudness over everything it is fed, so carrying one across books would blend two
    /// measurements into a gain that is wrong for both.
    func startGainStage() async {
        await meterFeed?.stop()
        let ring = SampleRing()
        let feed = LoudnessMeterFeed(ring: ring)
        sampleRing = ring
        meterFeed = feed
        await feed.start()
    }

    /// A fresh mix carrying its own tap, for one `AVPlayerItem`. Each item gets its own context
    /// so the retain taken at creation is balanced by exactly one release in that tap's finalize
    /// callback; they share the engine-lifetime `GainCell` and the per-book `SampleRing`.
    ///
    /// Returns `nil` before the first `load` — no ring, no gain stage, plain playback.
    func makeGainMix() -> AVAudioMix? {
        guard let sampleRing else { return nil }
        return GainTap.makeMix(context: GainTapContext(gain: gainCell, ring: sampleRing))
    }

    /// The R128 gain measured for the current book so far, or `nil` while unmeasurable.
    func currentMeasuredGainDb() async -> Float? {
        await meterFeed?.currentGainDb()
    }

    /// Stop measuring. Terminal, like the rest of `release()`.
    func stopGainStage() async {
        await meterFeed?.stop()
        meterFeed = nil
        sampleRing = nil
    }
}
