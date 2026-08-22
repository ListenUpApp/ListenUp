import Testing
@testable import ListenUp

/// Mirrors the Android side's `PushForegroundPolicyTest` case for case, so the two platforms are
/// provably answering the same question the same way.
struct PushForegroundPolicyTests {
    private func userInfo(_ json: String) -> [AnyHashable: Any] { ["payload": json] }

    @Test("a test notification presents in the foreground — the case that was broken")
    func testNotificationPresents() {
        #expect(PushForegroundPolicy.presentsInForeground(userInfo: userInfo(#"{"type":"test","sentAtMs":1}"#)))
    }

    @Test("an ordinary payload stays suppressed in the foreground")
    func ordinaryPayloadSuppressed() {
        #expect(!PushForegroundPolicy.presentsInForeground(userInfo: userInfo(#"{"type":"registration_approval","userId":"u1"}"#)))
    }

    @Test("an unknown discriminator stays suppressed rather than presenting by accident")
    func unknownSuppressed() {
        #expect(!PushForegroundPolicy.presentsInForeground(userInfo: userInfo(#"{"type":"who_knows"}"#)))
    }

    @Test("a malformed or absent payload never crashes and never presents")
    func malformedSuppressed() {
        #expect(!PushForegroundPolicy.presentsInForeground(userInfo: userInfo("not json")))
        #expect(!PushForegroundPolicy.presentsInForeground(userInfo: [:]))
        #expect(!PushForegroundPolicy.presentsInForeground(userInfo: ["payload": 42]))
    }
}
