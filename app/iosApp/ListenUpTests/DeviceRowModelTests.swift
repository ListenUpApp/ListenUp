import Testing
@preconcurrency import Shared
@testable import ListenUp

/// Pins the Devices screen's rule-8 boundary: the bridged Kotlin `DeviceRow` is snapshotted into a
/// native value once, and the list keys on the session id.
@Suite("DeviceRowModel")
struct DeviceRowModelTests {
    @Test func snapshotsEveryFieldTheScreenReads() {
        let row = DeviceRow(
            sessionId: "s-1",
            displayName: "Simon's iPhone",
            secondary: "iOS 26.5 · ListenUp 1.0.0",
            lastUsedAt: 1_700_000_000_000,
            isCurrent: true,
            deviceType: nil
        )
        let model = DeviceRowModel(row)
        #expect(model == DeviceRowModel(
            sessionId: "s-1",
            displayName: "Simon's iPhone",
            secondary: "iOS 26.5 · ListenUp 1.0.0",
            lastUsedAtMs: 1_700_000_000_000,
            isCurrent: true
        ))
        #expect(model.id == "s-1")
    }
}
