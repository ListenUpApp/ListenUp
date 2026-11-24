import SwiftUI
import Testing
@testable import ListenUp

@MainActor
@Suite("WithObserver", .serialized)
struct WithObserverTests {
    @Test("ObserverSlot creates its value once for a constant ID")
    func slotResolvesOnceForSameID() {
        let slot = ObserverSlot<String, FakeScreenObserver>()
        var builds = 0
        let first = slot.resolve(id: "a") {
            builds += 1
            return FakeScreenObserver(label: "first")
        }
        let second = slot.resolve(id: "a") {
            builds += 1
            return FakeScreenObserver(label: "unexpected")
        }

        #expect(builds == 1)
        #expect(first === second)
        #expect(second.label == "first")
    }

    @Test("ObserverSlot replaces and releases the previous observer when ID changes")
    func slotReplacesAndReleasesPreviousOnIDChange() {
        let slot = ObserverSlot<String, FakeScreenObserver>()
        weak var firstWeak: FakeScreenObserver?

        do {
            let first = slot.resolve(id: "a") { FakeScreenObserver(label: "a") }
            firstWeak = first
        }
        #expect(firstWeak != nil)

        let second = slot.resolve(id: "b") { FakeScreenObserver(label: "b") }
        #expect(firstWeak == nil)
        #expect(second.label == "b")
    }

    @Test("ObserverSlot releases its held observer on deinit")
    func slotReleasesObserverOnDeinit() {
        weak var weakObserver: FakeScreenObserver?
        do {
            let slot = ObserverSlot<ObserverDefaultID, FakeScreenObserver>()
            let resolved = slot.resolve(id: ObserverDefaultID()) { FakeScreenObserver(label: "owned") }
            weakObserver = resolved
            #expect(weakObserver != nil)
        }
        #expect(weakObserver == nil)
    }

    @Test("WithObserver renders initial state immediately and tracks observable mutations without re-creating")
    func withObserverRendersInitialStateAndTracksObservableChangesWithoutRecreating() async throws {
        var builds = 0
        let observer = FakeScreenObserver(label: "initial")
        let hosted = await HostedView(
            WithObserver { _ in
                builds += 1
                return observer
            } content: { model in
                Text(model.label)
            }
        )
        defer { hosted.close() }

        let initialStop = hosted.stop(labelled: "initial")
        #expect(initialStop != nil, "\(hosted.tree)")
        #expect(builds == 1)

        observer.label = "updated"
        await hosted.settle()

        #expect(hosted.stop(labelled: "updated") != nil, "\(hosted.tree)")
        #expect(builds == 1)
    }

    @Test("WithObserver preserves the observer across parent redraws and swaps it when id changes")
    func withObserverPreservesInstanceAcrossParentRerendersAndReplacesOnIDChange() async throws {
        let driver = FakeHostDriver(id: "first")
        var builds = 0
        let hosted = await HostedView(
            FakeParentView(driver: driver) { currentID in
                builds += 1
                return FakeScreenObserver(label: "for-\(currentID)")
            }
        )
        defer { hosted.close() }

        #expect(hosted.stop(labelled: "for-first") != nil, "\(hosted.tree)")
        #expect(builds == 1)

        driver.tick += 1
        await hosted.settle()
        #expect(hosted.stop(labelled: "for-first") != nil, "\(hosted.tree)")
        #expect(builds == 1)

        driver.id = "second"
        await hosted.settle()
        #expect(hosted.stop(labelled: "for-second") != nil, "\(hosted.tree)")
        #expect(builds == 2)
    }
}

@Observable
@MainActor
private final class FakeScreenObserver {
    var label: String

    init(label: String) {
        self.label = label
    }
}

@Observable
@MainActor
private final class FakeHostDriver {
    var id: String
    var tick = 0

    init(id: String) {
        self.id = id
    }
}

private struct FakeParentView: View {
    let driver: FakeHostDriver
    let makeObserver: @MainActor (String) -> FakeScreenObserver

    var body: some View {
        VStack {
            Text("tick-\(driver.tick)")
            WithObserver(id: driver.id) { _ in
                makeObserver(driver.id)
            } content: { observer in
                Text(observer.label)
            }
        }
    }
}
