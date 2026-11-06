import Foundation
import Testing
@testable import ListenUp

@MainActor
private final class FakeTask: ContinuedTask {
    let progress = Progress(totalUnitCount: 0)
    var expirationHandler: (() -> Void)?
    private(set) var completions: [Bool] = []
    private(set) var subtitles: [String] = []
    func setTaskCompleted(success: Bool) { completions.append(success) }
    func updateTitle(_ title: String, subtitle: String) { subtitles.append(subtitle) }
}

@MainActor
private final class FakeHold: UploadHold {
    private(set) var holds = 0
    private(set) var releases = 0
    func hold() { holds += 1 }
    func release() { releases += 1 }
}

@MainActor
private final class Harness {
    struct Refused: Error {}
    let hold = FakeHold()
    private(set) var submitted: [String] = []
    private(set) var launch: (@MainActor (any ContinuedTask) -> Void)?
    var refuse = false
    private(set) var cancelled = 0
    lazy var upload = ContinuedUpload(
        register: { [self] handler in launch = handler; return true },
        submit: { [self] identifier, _, _ in
            if refuse { throw Refused() }
            submitted.append(identifier)
        },
        fallback: hold,
        identifierSuffix: { "ID" }
    )

    func start() {
        upload.start(title: "Uploading books", subtitle: "File 1 of 2", cancel: { [self] in cancelled += 1 })
    }

    func running() -> FakeTask {
        start()
        let task = FakeTask()
        launch?(task)
        return task
    }
}

private func model(file: Int = 1, of count: Int = 2, fraction: Double? = nil) -> UploadProgressModel {
    UploadProgressModel(filename: "a.mp3", fileNumber: file, fileCount: count, fraction: fraction)
}

@Suite("ContinuedUpload")
@MainActor
struct ContinuedUploadTests {
    @Test func startSubmitsOneRequestAndASecondStartDoesNot() {
        let harness = Harness()
        harness.start()
        harness.start()
        #expect(harness.submitted.count == 1)
        #expect(harness.submitted.first?.hasSuffix(".upload.ID") == true)
    }

    @Test func progressFollowsTheSessionFractionAndNeverGoesBackwards() {
        let harness = Harness()
        let task = harness.running()
        #expect(task.progress.totalUnitCount == ContinuedUpload.totalUnits)
        harness.upload.update(model(fraction: 0.5), subtitle: "s")
        #expect(task.progress.completedUnitCount == 500)
        harness.upload.update(model(fraction: 0.25), subtitle: "s")
        #expect(task.progress.completedUnitCount == 500)
    }

    @Test func withoutAFractionOnlyFinishedFilesCount() {
        #expect(ContinuedUpload.units(for: model(file: 3, of: 4, fraction: nil)) == 500)
        #expect(ContinuedUpload.units(for: model(fraction: 2)) == ContinuedUpload.totalUnits)
    }

    @Test func finishCompletesTheTaskOnce() {
        let harness = Harness()
        let task = harness.running()
        harness.upload.finish(success: true)
        harness.upload.finish(success: true)
        #expect(task.completions == [true])
    }

    @Test func expirationCancelsTheUploadAndCompletesUnsuccessfully() async {
        let harness = Harness()
        let task = harness.running()
        task.expirationHandler?()
        await Task.yield()
        #expect(harness.cancelled == 1)
        #expect(task.completions == [false])
        harness.upload.finish(success: false)
        #expect(task.completions == [false])
    }

    @Test func aRefusedSubmitFallsBackToTheKeepAlive() {
        let harness = Harness()
        harness.refuse = true
        harness.start()
        #expect(harness.upload.isRunning)
        #expect(harness.hold.holds == 1)
        harness.upload.finish(success: true)
        #expect(harness.hold.releases == 1)
    }

    @Test func theFallbackCoversTheGapUntilTheSystemStartsTheTask() {
        let harness = Harness()
        let task = harness.running()
        #expect(harness.hold.holds == 1)
        #expect(harness.hold.releases == 1)
        #expect(task.completions.isEmpty)
    }

    @Test func aLateTaskForAnUploadAlreadyOverIsCompletedAtOnce() {
        let harness = Harness()
        harness.start()
        harness.upload.finish(success: false)
        let task = FakeTask()
        harness.launch?(task)
        #expect(task.completions == [false])
    }
}
