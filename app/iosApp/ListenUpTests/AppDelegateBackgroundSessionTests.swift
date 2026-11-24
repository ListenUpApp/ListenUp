import Foundation
import Testing
import UIKit
@testable import ListenUp

/// Stands in for the shared download service: counts attaches, and holds each drain open until the test lets it go.
private final class FakeEvents: BackgroundDownloadEvents {
    struct DrainFailed: Error {}

    private(set) var attaches = 0
    private(set) var drained: [String] = []
    var fails = false
    private var waiting: [String: CheckedContinuation<Void, Never>] = [:]

    func attach() {
        attaches += 1
    }

    func drain(identifier: String) async throws {
        drained.append(identifier)
        await withCheckedContinuation { continuation in
            waiting[identifier] = continuation
        }
        if fails { throw DrainFailed() }
    }

    func isDraining(_ identifier: String) -> Bool { waiting[identifier] != nil }

    func finishDrain(_ identifier: String) {
        waiting.removeValue(forKey: identifier)?.resume()
    }
}

/// Counts calls of a completion handler and records whether each came on the main thread.
private final class Handler {
    private(set) var calls = 0
    private(set) var onMain = true

    var completion: () -> Void {
        { [self] in
            onMain = onMain && Thread.isMainThread
            calls += 1
        }
    }
}

private func settle(until condition: @escaping @MainActor () -> Bool) async {
    for _ in 0 ..< 200 where !condition() {
        await Task.yield()
    }
}

/// The app delegate's half of background downloads: the system's completion handler stays in Swift and is called
/// exactly once, on the main actor, only after the shared layer has filed the wake's events.
@Suite("Background download hand-off")
struct AppDelegateBackgroundSessionTests {
    @Test func theHandlerWaitsForTheDrainAndIsCalledOnceOnTheMainActor() async {
        let events = FakeEvents()
        let handoff = BackgroundDownloadHandoff(events: events)
        let handler = Handler()

        handoff.handleEvents(identifier: "app.downloads", completionHandler: handler.completion)
        await settle { events.isDraining("app.downloads") }
        #expect(handler.calls == 0)

        events.finishDrain("app.downloads")
        await settle { handler.calls > 0 }
        #expect(handler.calls == 1)
        #expect(handler.onMain)
    }

    @Test func aDrainThatThrowsStillCallsTheHandler() async {
        let events = FakeEvents()
        events.fails = true
        let handoff = BackgroundDownloadHandoff(events: events)
        let handler = Handler()

        handoff.handleEvents(identifier: "app.downloads", completionHandler: handler.completion)
        await settle { events.isDraining("app.downloads") }
        events.finishDrain("app.downloads")
        await settle { handler.calls > 0 }

        #expect(handler.calls == 1)
    }

    @Test func twoSessionsKeepTheirOwnHandlers() async {
        let events = FakeEvents()
        let handoff = BackgroundDownloadHandoff(events: events)
        let any = Handler()
        let unmetered = Handler()

        handoff.handleEvents(identifier: "app.downloads", completionHandler: any.completion)
        handoff.handleEvents(identifier: "app.downloads.unmetered", completionHandler: unmetered.completion)
        await settle { events.isDraining("app.downloads") && events.isDraining("app.downloads.unmetered") }

        events.finishDrain("app.downloads.unmetered")
        await settle { unmetered.calls > 0 }
        #expect(unmetered.calls == 1)
        #expect(any.calls == 0)

        events.finishDrain("app.downloads")
        await settle { any.calls > 0 }
        #expect(any.calls == 1)
        #expect(unmetered.calls == 1)
    }

    @Test func aSecondWakeForASessionStillDrainingJoinsIt() async {
        let events = FakeEvents()
        let handoff = BackgroundDownloadHandoff(events: events)
        let first = Handler()
        let second = Handler()

        handoff.handleEvents(identifier: "app.downloads", completionHandler: first.completion)
        await settle { events.isDraining("app.downloads") }
        handoff.handleEvents(identifier: "app.downloads", completionHandler: second.completion)
        events.finishDrain("app.downloads")
        await settle { first.calls > 0 && second.calls > 0 }

        #expect(events.drained == ["app.downloads"])
        #expect(first.calls == 1)
        #expect(second.calls == 1)
    }

    @Test func launchingReattachesTheSessionsWhateverTheSignInState() {
        let events = FakeEvents()
        let delegate = AppDelegate()
        delegate.backgroundDownloads = BackgroundDownloadHandoff(events: events)

        let launched = delegate.application(UIApplication.shared, didFinishLaunchingWithOptions: nil)

        #expect(launched)
        #expect(events.attaches == 1)
    }

    @Test func theAppDelegateHandsAWakeToTheHandoff() async {
        let events = FakeEvents()
        let delegate = AppDelegate()
        delegate.backgroundDownloads = BackgroundDownloadHandoff(events: events)
        let handler = Handler()

        delegate.application(UIApplication.shared, handleEventsForBackgroundURLSession: "app.downloads",
                             completionHandler: handler.completion)
        await settle { events.isDraining("app.downloads") }
        events.finishDrain("app.downloads")
        await settle { handler.calls > 0 }

        #expect(handler.calls == 1)
    }
}
