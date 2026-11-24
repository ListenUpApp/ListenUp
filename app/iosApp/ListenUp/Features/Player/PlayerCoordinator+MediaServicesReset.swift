import Foundation

// A media-services reset kills every audio object the app holds. It reaches the player as an audio-session event
// (`AudioSessionEvent.mediaServicesReset`), through `steps`, in order with interruptions and route changes.
extension PlayerCoordinator {
    /// Save the place, rebuild the engine's player, and leave the book in the could-not-start state: Play or
    /// Retry then prepares it afresh on a live player. Nothing resumes by itself.
    func applyMediaServicesReset() async {
        let held = engineBook
        // A book still loading has not moved from its saved place, and saving now would overwrite it.
        if let held, !isEngineLoading { recordPlaceBeforeDropping(held.bookId) }
        let wasBusy = held != nil || isEngineLoading
        supersedeLoad()
        await engine.rebuildAfterMediaServicesReset()
        guard wasBusy else { return }
        pauseCause = nil
        fail(.couldNotStart(detail: "media services reset"))
    }
}
