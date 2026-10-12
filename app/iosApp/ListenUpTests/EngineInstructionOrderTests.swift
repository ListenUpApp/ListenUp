import Testing
@testable import ListenUp

/// The shared player's instructions reach AVAudioEngine in the order it issued them (spec risk R2):
/// one bridge collector feeds one stream that one coordinator task drains.
@Suite("Engine instructions run in order")
@MainActor
struct EngineInstructionOrderTests {
    @Test("Load → SeekTo → Play reaches the engine in that order")
    func ordered() async throws {
        let harness = PlayerHarness()
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        harness.instructions.send(.seek(toMs: 30_000))
        harness.instructions.send(.play)
        await harness.engine.waitForPlayCount(1)
        await harness.engine.waitUntilSeeked()
        let log = await harness.engine.commandLog
        #expect((log.firstIndex(of: "load") ?? .max) < (log.firstIndex(of: "seek") ?? .max))
        #expect((log.firstIndex(of: "seek") ?? .max) < (log.lastIndex(of: "play") ?? .max))
        #expect(await harness.engine.lastSeekMs == 30_000)
    }

    @Test("A pause sent while the book loads leaves it loaded and paused")
    func pauseWhileLoading() async throws {
        let harness = PlayerHarness()
        await harness.engine.setBlockLoad(true)
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        await harness.engine.waitForLoadEntered()
        await harness.run(.pause)
        await harness.engine.releaseLoad()

        await awaitObservation { harness.coordinator.enginePhase == .paused }
        #expect(await harness.engine.playCount == 0)
        #expect(harness.progress.startedCalls.isEmpty)
        #expect(harness.coordinator.engineBook?.bookId == "b1")
    }

    @Test("SetVolume reaches the engine (the shared sleep fade and Keep listening)")
    func volume() async throws {
        let harness = PlayerHarness()
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        harness.instructions.send(.setVolume(0.2))
        harness.instructions.send(.setVolume(1.0))
        await awaitUntil { await harness.engine.commandLog.filter { $0 == "volume" }.count == 2 }
        #expect(await harness.engine.lastVolume == 1.0)
    }

    @Test("Stop unloads the engine without echoing an unload back to the shared player")
    func stop() async throws {
        let harness = PlayerHarness()
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        harness.instructions.send(.stop)
        await harness.engine.waitUntilUnloaded()
        #expect(await harness.engine.didUnload)
        // The shared player issued the stop and already knows; an echo could land after its next load.
        #expect(harness.facts.unloadedCount == 0)
        #expect(harness.coordinator.engineBook == nil)
    }

    @Test("The coordinator keeps its instruction source alive, so the shared player's commands keep flowing")
    func retainsInstructionSource() {
        // Production hands the coordinator a source nothing else holds (`KotlinEngineInstructions`, whose
        // FlowBridge collects NativePlayerEngine.commands). If the coordinator drops it, the bridge cancels on
        // deinit and every Load/Play/Pause piles up unread: on device, Play spun forever.
        weak var weakSource: FakeEngineInstructions?
        let coordinator: PlayerCoordinator = {
            let source = FakeEngineInstructions()
            weakSource = source
            return PlayerCoordinator(
                instructions: source, facts: FakeEngineFacts(), progress: FakeProgressReporting(),
                engine: FakePlaybackEngine())
        }()
        #expect(weakSource != nil)
        withExtendedLifetime(coordinator) {}
    }
}
