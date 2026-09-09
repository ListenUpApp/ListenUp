import Testing
@testable import ListenUp

@Suite("URL redaction for logs")
struct UrlRedactionTests {
    @Test func stripsTheSignedQuery() {
        let signed = "https://books.example.test/api/v1/audio/b1/f1?u=user&exp=123&sig=deadbeef"
        let redacted = UrlRedaction.withoutQuery(signed)
        #expect(redacted == "https://books.example.test/api/v1/audio/b1/f1")
        #expect(redacted.contains("sig=") == false)
        #expect(redacted.contains("exp=") == false)
    }

    @Test func handlesNilAndGarbage() {
        #expect(UrlRedaction.withoutQuery(nil) == "—")
        #expect(UrlRedaction.withoutQuery("").isEmpty == false)
    }

    @Test func keepsARelativePath() {
        #expect(UrlRedaction.withoutQuery("/api/v1/audio/b1/f1?sig=x") == "/api/v1/audio/b1/f1")
    }
}
