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
        // End any activity left over from a previous app session.
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

        coverWriter?.write(
            bookId: snapshot.bookId, coverPath: snapshot.coverPath, blurHash: snapshot.coverBlurHash
        )
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
        Task { await ending.end(nil, dismissalPolicy: .immediate) }
    }
}
