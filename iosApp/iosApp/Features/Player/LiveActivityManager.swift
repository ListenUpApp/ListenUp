// `@preconcurrency`: ActivityKit's `Activity` is not `Sendable`, so awaiting its
// `nonisolated` `update`/`end` from this `@MainActor` manager trips Swift 6 strict
// concurrency. The activity handle is a system-managed token, safe to use across
// contexts; the attribute downgrades the diagnostic until ActivityKit is audited.
@preconcurrency import ActivityKit
import SwiftUI

/// Manages the audiobook Live Activity (Dynamic Island + Lock Screen).
///
/// Starts a Live Activity when playback begins, updates it with chapter/progress
/// changes, and ends it when playback stops.
@MainActor
final class LiveActivityManager {

    private let observer: PlayerCoordinator
    private var activity: Activity<AudiobookActivityAttributes>?
    private var updateTask: Task<Void, Never>?
    private var wasVisible = false

    init(observer: PlayerCoordinator) {
        self.observer = observer
        startMonitoring()
    }

    deinit {
        updateTask?.cancel()
    }

    // MARK: - Monitoring

    private func startMonitoring() {
        updateTask = Task {
            while !Task.isCancelled {
                await updateActivityState()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private func updateActivityState() async {
        if observer.isVisible && !wasVisible {
            await startActivity()
            wasVisible = true
        } else if !observer.isVisible && wasVisible {
            await endActivity()
            wasVisible = false
        } else if observer.isVisible {
            await updateActivity()
        }
    }

    // MARK: - Activity Lifecycle

    private func startActivity() async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        let attributes = AudiobookActivityAttributes(
            bookTitle: observer.bookTitle,
            authorName: observer.authorName,
            coverBlurHash: observer.coverBlurHash
        )

        let state = makeContentState()

        do {
            activity = try Activity.request(
                attributes: attributes,
                content: .init(state: state, staleDate: nil),
                pushType: nil
            )
        } catch {
            print("Failed to start Live Activity: \(error)")
        }
    }

    private func updateActivity() async {
        guard let activity else { return }
        let state = makeContentState()
        await activity.update(.init(state: state, staleDate: nil))
    }

    private func endActivity() async {
        guard let activity else { return }
        let state = makeContentState()
        await activity.end(.init(state: state, staleDate: nil), dismissalPolicy: .immediate)
        self.activity = nil
    }

    // MARK: - State

    private func makeContentState() -> AudiobookActivityAttributes.ContentState {
        AudiobookActivityAttributes.ContentState(
            chapterTitle: observer.chapterTitle ?? observer.bookTitle,
            isPlaying: observer.isPlaying,
            progress: observer.bookProgress,
            chapterProgress: chapterProgress,
            elapsedFormatted: formatDuration(ms: observer.bookPositionMs),
            remainingFormatted: formatRemaining()
        )
    }

    private var chapterProgress: Float {
        guard observer.chapterDurationMs > 0 else { return 0 }
        return Float(observer.chapterPositionMs) / Float(observer.chapterDurationMs)
    }

    private func formatDuration(ms: Int64) -> String {
        let totalSeconds = Int(ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        if hours > 0 {
            return "\(hours)h \(minutes)m"
        }
        return "\(minutes)m"
    }

    private func formatRemaining() -> String {
        let remaining = observer.bookDurationMs - observer.bookPositionMs
        return formatDuration(ms: remaining) + " left"
    }
}
