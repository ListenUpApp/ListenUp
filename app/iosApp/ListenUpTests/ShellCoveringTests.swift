import SwiftUI
import Testing
@testable import ListenUp

@Suite("The readiness gate covers the tab shell instead of replacing it")
struct ShellCoveringLayoutTests {
    @Test(arguments: [LibraryReadinessPhase.ready, .checkFailed], [false, true])
    func aReadyLibraryShowsTheShellUncovered(phase: LibraryReadinessPhase, mounted: Bool) {
        #expect(
            ShellCoveringLayout.layout(for: phase, shellHasMounted: mounted)
                == ShellCoveringLayout(showsShell: true, covering: .none))
    }

    @Test(arguments: [
        (LibraryReadinessPhase.checking, ShellCovering.launch),
        (.needsSetup, .setup),
        (.populating, .populating)
    ])
    func beforeTheShellHasMountedTheGateScreenStandsInForIt(phase: LibraryReadinessPhase, covering: ShellCovering) {
        #expect(
            ShellCoveringLayout.layout(for: phase, shellHasMounted: false)
                == ShellCoveringLayout(showsShell: false, covering: covering))
    }

    @Test(arguments: [
        (LibraryReadinessPhase.checking, ShellCovering.launch),
        (.needsSetup, .setup),
        (.populating, .populating)
    ])
    func afterTheShellHasMountedItStaysAndTheGateScreenCoversIt(phase: LibraryReadinessPhase, covering: ShellCovering) {
        #expect(
            ShellCoveringLayout.layout(for: phase, shellHasMounted: true)
                == ShellCoveringLayout(showsShell: true, covering: covering))
    }
}

@MainActor
@Suite("The tab shell keeps one identity through a readiness re-check", .serialized)
struct ShellCoveringIdentityTests {
    @Observable
    @MainActor
    final class PhaseModel {
        var phase: LibraryReadinessPhase = .ready
    }

    private final class Token {}

    private final class Recorder {
        var identities: [ObjectIdentifier] = []
    }

    private struct Probe: View {
        let recorder: Recorder
        @State private var token = Token()

        var body: some View {
            Color.clear.onAppear { recorder.identities.append(ObjectIdentifier(token)) }
        }
    }

    private struct Harness: View {
        let model: PhaseModel
        let recorder: Recorder

        var body: some View {
            ShellCoveringHost(phase: model.phase) {
                Probe(recorder: recorder)
            } cover: { _ in
                Color.red
            }
        }
    }

    @Test func theShellIsTheSameInstanceAfterReadyCheckingReady() async {
        let model = PhaseModel()
        let recorder = Recorder()
        let hosted = await HostedView(Harness(model: model, recorder: recorder))
        defer { hosted.close() }

        model.phase = .checking
        await hosted.settle()
        model.phase = .ready
        await hosted.settle()

        #expect(!recorder.identities.isEmpty)
        #expect(Set(recorder.identities).count == 1, "the shell was rebuilt: \(recorder.identities.count) appearances")
    }
}
