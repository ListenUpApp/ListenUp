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
            deviceType: nil,
            glyph: .Unknown
        )
        let model = DeviceRowModel(row)
        #expect(model == DeviceRowModel(
            sessionId: "s-1",
            displayName: "Simon's iPhone",
            secondary: "iOS 26.5 · ListenUp 1.0.0",
            lastUsedAtMs: 1_700_000_000_000,
            isCurrent: true,
            glyph: .Unknown
        ))
        #expect(model.id == "s-1")
    }

    @Test func eachKindOfDeviceDrawsItsOwnSymbol() {
        let symbols = [
            DeviceGlyph.Phone, .Tablet, .Computer, .Browser, .Tv, .Watch, .Car, .Headset, .Cast, .Speaker, .Unknown
        ].map(DeviceRowModel.symbolName(for:))
        #expect(symbols == [
            "iphone.gen3", "ipad", "laptopcomputer", "globe", "tv", "applewatch", "car", "visionpro",
            "airplayvideo", "hifispeaker", "ipad.and.iphone"
        ])
        #expect(Set(symbols).count == symbols.count)
    }

    @Test func aRowsGlyphSurvivesTheBridgeInsideARealList() {
        let ready = DevicesUiStateReady(
            devices: [
                DeviceRow(
                    sessionId: "s-1", displayName: "iPad", secondary: "", lastUsedAt: 1,
                    isCurrent: true, deviceType: "tablet", glyph: .Tablet
                ),
                DeviceRow(
                    sessionId: "s-2", displayName: "Chrome", secondary: "", lastUsedAt: 2,
                    isCurrent: false, deviceType: "browser", glyph: .Browser
                )
            ],
            signingOut: []
        )
        let models = ready.devices.map(DeviceRowModel.init)
        #expect(models.map(\.glyph) == [.Tablet, .Browser])
        #expect(models.map(\.symbolName) == ["ipad", "globe"])
    }
}
