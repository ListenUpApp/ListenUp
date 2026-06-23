@preconcurrency import ActivityKit
import ListenUpActivityKit
import SwiftUI

/// Drives the audiobook Live Activity from `PlayerCoordinator`'s discrete
/// playback events. No polling timer — `start`, `sync`, and `end` are called by
/// the coordinator exactly when playback state changes.
@MainActor
final class LiveActivityManager {

    private var activity: Activity<AudiobookActivityAttributes>?
    private let coverWriter = CoverThumbnailWriter()

    init() {
        // End any activity left over from a previous app session. Fire-and-forget by
        // design: this is best-effort one-shot cleanup the system completes on its own
        // schedule — nothing in this object's lifecycle depends on it finishing.
        for stale in Activity<AudiobookActivityAttributes>.activities {
            Task { await stale.end(nil, dismissalPolicy: .immediate) }
        }
    }

    /// Start (or restart) the activity for the book described by `snapshot`.
    /// `ActivityAttributes` static fields cannot change, so a new book ends the
    /// old activity and requests a fresh one.
    func start(_ snapshot: LiveActivitySnapshot) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        if let activity, activity.attributes.bookId == snapshot.bookId {
            sync(snapshot)
            return
        }
        endActivity()

        // Render + persist the cover thumbnail OFF the main thread (ImageIO decode, BlurHash
        // render, JPEG encode, disk write), THEN request the activity so its cover file is ready.
        // Doing this synchronously on the main actor hitched every playback start.
        let writer = coverWriter
        let bookId = snapshot.bookId
        let coverPath = snapshot.coverPath
        let blurHash = snapshot.coverBlurHash
        Task { [weak self] in
            _ = await Task.detached {
                writer?.write(bookId: bookId, coverPath: coverPath, blurHash: blurHash)
            }.value
            self?.requestActivity(snapshot)
        }
    }

    /// Request the Live Activity. Split out of `start` so the cover write can finish off-main first.
    private func requestActivity(_ snapshot: LiveActivitySnapshot) {
        do {
            activity = try Activity.request(
                attributes: LiveActivityContentMapper.attributes(from: snapshot),
                content: .init(
                    state: LiveActivityContentMapper.contentState(from: snapshot),
                    staleDate: nil
                )
            )
        } catch {
            Log.error("Live Activity request failed", error: error)
        }
    }

    /// Push an updated `ContentState`. A no-op when no activity is running.
    func sync(_ snapshot: LiveActivitySnapshot) {
        guard let activity else { return }
        let state = LiveActivityContentMapper.contentState(from: snapshot)
        // Fire-and-forget by design: a transient, latest-wins UI push. A superseded
        // update is harmless, so there is nothing to await or cancel.
        Task { await activity.update(.init(state: state, staleDate: nil)) }
    }

    /// End the activity and remove its cover thumbnail.
    func end() {
        endActivity()
    }

    private func endActivity() {
        guard let activity else { return }
        let bookId = activity.attributes.bookId
        let ending = activity
        self.activity = nil
        coverWriter?.remove(bookId: bookId)
        // Fire-and-forget by design: `self.activity` is already cleared, so the manager's
        // state is fully torn down synchronously. The actual dismissal is OS-managed and
        // nothing waits on it; capturing `ending` locally keeps it alive until it ends.
        Task { await ending.end(nil, dismissalPolicy: .immediate) }
    }
}
