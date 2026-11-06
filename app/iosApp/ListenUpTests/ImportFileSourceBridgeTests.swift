import Foundation
import Testing
@testable import ListenUp

/// Pins `ImportFileSourceBridge.readData`'s contract: bytes come back whole, an empty file and a
/// missing file each map to their own `ImportFilePickError`.
@Suite("ImportFileSourceBridge.readData")
struct ImportFileSourceBridgeTests {
    private func temporaryFileURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    @Test func returnsTheBytesOfAFile() async throws {
        let url = temporaryFileURL()
        let bytes = Data([1, 2, 3, 4])
        try bytes.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let data = try await ImportFileSourceBridge.readData(from: url)

        #expect(data == bytes)
    }

    @Test func anEmptyFileThrowsEmpty() async throws {
        let url = temporaryFileURL()
        try Data().write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        await #expect(throws: ImportFilePickError.empty) {
            try await ImportFileSourceBridge.readData(from: url)
        }
    }

    @Test func aMissingFileThrowsUnreadable() async {
        let url = temporaryFileURL()

        await #expect(throws: ImportFilePickError.unreadable) {
            try await ImportFileSourceBridge.readData(from: url)
        }
    }
}
