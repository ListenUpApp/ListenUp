import Foundation

/// The gain inputs for the loaded book, plus the last measurement persisted for it.
///
/// Grouped into one value so the coordinator carries a single stored property: Swift extensions
/// cannot declare stored properties, and `PlayerCoordinator.swift` is at its file-length cap.
struct GainState {
    /// The user's boost for this book, in dB. Survives a book switch, like `playbackSpeed`.
    var boostDb: Float = 0
    /// Client-measured R128 gain, `nil` until enough audio has been measured. Per-book.
    var measuredGainDb: Float?
    /// Server tag-read normalization gain, `nil` when the file carries no tag. Per-book.
    var normalizationGainDb: Float?
    /// The measurement last handed to the reporter, so an unmoved reading isn't re-saved.
    var lastSavedMeasuredGainDb: Float?

    /// Swift mirror of Kotlin `VolumeGain.effectiveGainDb`
    /// (`app/sharedLogic/.../client/playback/loudness/VolumeGain.kt`) — kept in Swift rather than
    /// called across the bridge because it runs on the load path. Normalization is always on: a
    /// real measurement is trusted over a possibly-stale file tag, both fall back to 0 dB, and
    /// the user's boost adds on top. Change one side and the other must follow.
    var effectiveDb: Float { (measuredGainDb ?? normalizationGainDb ?? 0) + boostDb }

    init() {}

    /// Adopt a freshly prepared book's inputs, carrying nothing over from the previous one.
    init(prepared: PreparedPlayback) {
        boostDb = prepared.resumeBoostDb
        measuredGainDb = prepared.measuredGainDb
        normalizationGainDb = prepared.normalizationGainDb
    }

    /// Drop the per-book measurement inputs on a switch; the user's boost carries over so the
    /// player doesn't flash back to 0 dB before the incoming book's prepare resolves.
    mutating func clearMeasurements() {
        measuredGainDb = nil
        normalizationGainDb = nil
        lastSavedMeasuredGainDb = nil
    }
}

/// The coordinator's volume-boost stage: the read surface the boost UI binds to, the two user
/// writes, and the measurement persistence. Split out of `PlayerCoordinator.swift` to keep that
/// file inside its 800-line cap — the same split `AudioEngine+Gain.swift` makes for the engine.
/// `engine`, `progress` and `gain` are internal (not private) so this file can reach them.
@MainActor
extension PlayerCoordinator {
    /// The user's volume boost for the loaded book, in dB — what the boost control renders.
    var volumeBoostDb: Float { gain.boostDb }

    /// Adopt the prepared book's gain inputs and push the combined gain to the engine. Called
    /// once per load, alongside `setRate`: normalization is always on, so every book starts at
    /// its normalized level whether or not the user has set a boost.
    func applyGain(from prepared: PreparedPlayback) async {
        gain = GainState(prepared: prepared)
        await engine.setGainDb(gain.effectiveDb)
    }

    /// The user picked a boost for this book. Mirrors `setSpeed`: apply live, then persist.
    func setBoost(_ db: Float) {
        applyBoost(db)
        if let id = phase.playingState?.bookId {
            progress.onVolumeBoostChanged(bookId: id, positionMs: bookPositionMs, newBoostDb: db)
        }
    }

    /// The user cleared this book's override back to the global default. Reported as a reset
    /// rather than a change so the per-book override is actually removed, not overwritten.
    func resetBoost(defaultDb: Float) {
        applyBoost(defaultDb)
        if let id = phase.playingState?.bookId {
            progress.onBoostReset(bookId: id, positionMs: bookPositionMs, defaultBoostDb: defaultDb)
        }
    }

    /// Persist a refined R128 measurement once playback settles — driven by the `phase` didSet,
    /// which makes `.paused` (a pause of any origin, and the book-finished transition) the single
    /// trigger, so no pause path can forget it.
    ///
    /// Deliberately does NOT re-apply the engine gain: the measurement refines continuously while
    /// a book plays, and reacting to it live would be an audible level jump mid-sentence. The new
    /// value takes effect at the next load, or the next time the user moves the boost.
    func saveRefinedMeasurement() {
        guard case .paused(let loaded) = phase else { return }
        let positionMs = bookPositionMs
        Task {
            guard let measured = await engine.currentMeasuredGainDb() else { return }
            // 0.1 dB is well below audibility — smaller movement is meter jitter, not a
            // refinement, and re-saving it would churn the sync queue for nothing.
            if let last = gain.lastSavedMeasuredGainDb, abs(measured - last) <= 0.1 { return }
            gain.lastSavedMeasuredGainDb = measured
            progress.onMeasuredGain(bookId: loaded.bookId, positionMs: positionMs, gainDb: measured)
        }
    }

    private func applyBoost(_ db: Float) {
        gain.boostDb = db
        Task { await engine.setGainDb(gain.effectiveDb) }
    }
}
