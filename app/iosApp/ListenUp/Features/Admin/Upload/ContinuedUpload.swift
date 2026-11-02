import BackgroundTasks
import Foundation

/// The slice of `BGContinuedProcessingTask` the controller drives — a protocol so a test can stand in
/// for a task the system only hands out to a real, backgrounded app.
protocol ContinuedTask: AnyObject {
    var progress: Progress { get }
    var expirationHandler: (() -> Void)? { get set }
    func setTaskCompleted(success: Bool)
    func updateTitle(_ title: String, subtitle: String)
}

extension BGContinuedProcessingTask: ContinuedTask {}

/// What keeps an upload going when no continued-processing task can: the screen stays awake and a
/// short background task lets the file in flight finish.
@MainActor
protocol UploadHold: AnyObject {
    func hold()
    func release()
}

/// Keeps a user-started upload running after the admin leaves the app, as an iOS 26
/// `BGContinuedProcessingTask`: the system shows the upload's progress in its own UI and lets the
/// work continue for as long as that progress keeps moving.
///
/// **Lifecycle.** `start` submits a request; the system answers by launching the registered handler,
/// which hands the controller its task (`attach`). `update` moves the task's `Progress`, `finish`
/// completes it. If iOS expires the task, the upload is cancelled through the `cancel` hook the caller
/// passed to `start` — honestly, not left to run unseen — and the task completes unsuccessfully.
///
/// **Never stranded.** If the request cannot be submitted (not permitted, the system refuses), the
/// upload falls back to `UploadHold`: the screen stays awake and a short background task covers the
/// file in flight. The same hold covers the moment between submitting and the system starting the
/// task, so a request the system declines to start is not left with nothing keeping the upload alive.
///
/// The system requires the task to report progress and may expire one that looks stalled; the upload
/// phase after the last byte (the server importing) holds the final value rather than inventing any.
@MainActor
final class ContinuedUpload {
    /// The `Progress` total the controller maps the upload onto.
    static let totalUnits: Int64 = 1000

    /// Submit a request for a task. Throws when the system refuses it.
    typealias Submit = @MainActor (_ identifier: String, _ title: String, _ subtitle: String) throws -> Void
    /// Register the handler the system calls with the task. False if the identifier is not permitted.
    typealias Register = @MainActor (_ launch: @escaping @MainActor (any ContinuedTask) -> Void) -> Bool

    /// Shared, because the system allows one launch handler per identifier for the process's life.
    static let shared = ContinuedUpload()

    private enum State {
        case idle
        /// Submitted; the system has not yet started the task.
        case requested
        case running(any ContinuedTask)
        /// No task could be had; the fallback hold is keeping the upload alive.
        case fallback
    }

    private let submit: Submit
    private let fallback: UploadHold
    private let identifierSuffix: @MainActor () -> String
    private let isRegistered: Bool
    private var state = State.idle
    private var cancelUpload: (() -> Void)?
    private var completedUnits: Int64 = 0
    private var title = ""

    init(
        register: Register = ContinuedUpload.registerWithScheduler,
        submit: @escaping Submit = ContinuedUpload.submitToScheduler,
        fallback: UploadHold = UploadKeepAlive(),
        identifierSuffix: @escaping @MainActor () -> String = { UUID().uuidString }
    ) {
        self.submit = submit
        self.fallback = fallback
        self.identifierSuffix = identifierSuffix
        // `self` is not fully formed until every property is set, so register through a local.
        var handler: (@MainActor (any ContinuedTask) -> Void)?
        isRegistered = register { task in handler?(task) }
        handler = { [weak self] task in self?.attach(task) }
    }

    /// Whether an upload is being kept alive, by a task or by the fallback.
    var isRunning: Bool {
        if case .idle = state { false } else { true }
    }

    /// Begin keeping an upload alive. A second call while one is running does nothing.
    /// `cancel` stops the upload; it is called if the system expires the task.
    func start(title: String, subtitle: String, cancel: @escaping () -> Void) {
        guard !isRunning else { return }
        cancelUpload = cancel
        completedUnits = 0
        self.title = title
        guard isRegistered else {
            beginFallback()
            return
        }
        do {
            try submit(Self.identifierPrefix + identifierSuffix(), title, subtitle)
            state = .requested
            fallback.hold()
        } catch {
            Log.error("Continued upload refused, using the short background task", error: error)
            beginFallback()
        }
    }

    /// Move the task's progress to the upload's. Never goes backwards.
    func update(_ upload: UploadProgressModel, subtitle: String) {
        completedUnits = max(completedUnits, Self.units(for: upload))
        guard case .running(let task) = state else { return }
        publish(to: task)
        task.updateTitle(title, subtitle: subtitle)
    }

    /// The upload is over, one way or another. Completes the task once; a second call does nothing.
    func finish(success: Bool) {
        switch state {
        case .idle:
            return
        case .running(let task):
            task.expirationHandler = nil
            task.setTaskCompleted(success: success)
        case .requested, .fallback:
            break
        }
        fallback.release()
        state = .idle
        cancelUpload = nil
    }

    // MARK: - From the system

    private func attach(_ task: any ContinuedTask) {
        guard case .requested = state else {
            // Nothing is waiting for it — a late task for an upload already over.
            task.setTaskCompleted(success: false)
            return
        }
        task.progress.totalUnitCount = Self.totalUnits
        publish(to: task)
        // The queue the system calls this on is not specified, so it hops to the main actor rather than
        // assume it.
        task.expirationHandler = { [weak self] in
            Task { @MainActor in self?.expire() }
        }
        state = .running(task)
        fallback.release()
    }

    /// iOS is taking the task back: stop the upload honestly and say the work did not complete.
    private func expire() {
        guard case .running(let task) = state else { return }
        let cancel = cancelUpload
        task.expirationHandler = nil
        task.setTaskCompleted(success: false)
        state = .idle
        cancelUpload = nil
        cancel?()
    }

    // MARK: - Helpers

    private func beginFallback() {
        state = .fallback
        fallback.hold()
    }

    private func publish(to task: any ContinuedTask) {
        task.progress.completedUnitCount = completedUnits
    }

    /// The upload's place in `0...totalUnits`. The shared fraction already spans the whole session; when
    /// the selection could not report sizes, whole finished files are all that can be said.
    static func units(for upload: UploadProgressModel) -> Int64 {
        let fraction = upload.fraction
            ?? Double(max(upload.fileNumber - 1, 0)) / Double(max(upload.fileCount, 1))
        return Int64((min(max(fraction, 0), 1) * Double(totalUnits)).rounded())
    }

    // MARK: - The real scheduler

    /// Must match the `Info.plist` `BGTaskSchedulerPermittedIdentifiers` entry, minus its `*`. A
    /// continued-processing identifier has to begin with the app's bundle identifier.
    private nonisolated static var identifierPrefix: String { "\(Bundle.main.bundleIdentifier ?? "com.calypsan.listenup").upload." }

    private static func registerWithScheduler(
        _ launch: @escaping @MainActor (any ContinuedTask) -> Void
    ) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: identifierPrefix + "*",
            // The main queue, so the launch handler (main-actor isolated here) runs where it claims to.
            using: .main
        ) { task in
            guard let task = task as? BGContinuedProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            launch(task)
        }
    }

    private static func submitToScheduler(identifier: String, title: String, subtitle: String) throws {
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: subtitle)
        // Fail rather than queue: a queued request could start long after the admin has stopped caring,
        // and the fallback hold is the honest answer to a system that cannot run it now.
        request.strategy = .fail
        try BGTaskScheduler.shared.submit(request)
    }
}
