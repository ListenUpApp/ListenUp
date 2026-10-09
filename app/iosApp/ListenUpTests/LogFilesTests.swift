import Testing
import Foundation
@testable import ListenUp

/// The pieces of Settings → Share logs that are worth pinning: the files arrive as file URLs in the
/// order the core lists them (oldest first), and a file line carries what OSLog keeps private.
@Suite("LogFiles")
struct LogFilesTests {
    @Test func pathsBecomeFileURLsInOrder() {
        let urls = LogFiles.urls(fromPaths: ["/logs/listenup.log.1", "/logs/listenup.log"])

        #expect(urls.map(\.path) == ["/logs/listenup.log.1", "/logs/listenup.log"])
        #expect(urls.allSatisfy(\.isFileURL))
    }

    @Test func noPathsMeansNothingToShare() {
        #expect(LogFiles.urls(fromPaths: []).isEmpty)
    }

    @Test func fileLineCarriesTheDetail() {
        #expect(Log.fileLine("Playback failed", detail: "track 3") == "Playback failed — track 3")
        #expect(Log.fileLine("Playback failed", detail: nil) == "Playback failed")
    }

    private struct SampleError: LocalizedError {
        var errorDescription: String? { "the disk is full" }
    }

    @Test func fileLineNamesTheErrorTypeAndItsDescription() {
        let line = Log.fileLine("Download failed", detail: "book-42", error: SampleError())

        #expect(line.hasPrefix("Download failed: "))
        #expect(line.contains("SampleError"))
        #expect(line.contains("the disk is full"))
        #expect(line.hasSuffix(" — book-42"))
    }
}
