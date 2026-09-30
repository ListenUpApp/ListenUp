import Testing
@testable import ListenUp

/// Pins the mirrored server URL covers build from: an empty or missing URL is "no server", never an
/// empty base that would produce a malformed cover URL.
@MainActor
@Suite("ImageServerBase")
struct ImageServerBaseTests {
    @Test func mirrorsThePublishedURL() {
        let base = ImageServerBase()
        base.update("https://listen.example")
        #expect(base.url == "https://listen.example")
    }

    @Test func emptyOrMissingMeansNoServer() {
        let base = ImageServerBase()
        base.update("https://listen.example")
        base.update("")
        #expect(base.url == nil)
        base.update("https://listen.example")
        base.update(nil)
        #expect(base.url == nil)
    }
}
