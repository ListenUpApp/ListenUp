import Foundation
import Testing
@testable import ListenUp

@MainActor
@Suite("PlayerRequestRouter")
struct PlayerRequestRouterTests {
    @Test func theFrontWindowClaimsARequestOnce() {
        let router = PlayerRequestRouter()
        let back = UUID()
        let front = UUID()
        router.sceneBecameActive(back)
        router.sceneBecameActive(front)
        router.post(.openBook("b1"))

        #expect(router.claim(for: back) == nil)
        #expect(router.claim(for: front) == .openBook("b1"))
        #expect(router.claim(for: front) == nil)
        #expect(router.claim(for: back) == nil)
    }

    @Test func beforeAnyWindowReportsTheFirstClaimerWins() {
        let router = PlayerRequestRouter()
        router.post(.openInbox)

        #expect(router.claim(for: UUID()) == .openInbox)
        #expect(router.claim(for: UUID()) == nil)
    }

    @Test func aClosedFrontWindowReleasesTheRequest() {
        let router = PlayerRequestRouter()
        let front = UUID()
        let other = UUID()
        router.sceneBecameActive(front)
        router.post(.signIn)
        router.sceneWentAway(front)

        #expect(router.claim(for: other) == .signIn)
    }

    @Test func aDocumentRequestCarriesTheDocument() {
        let router = PlayerRequestRouter()
        let document = ReaderDocument(localPath: "/tmp/a.pdf", title: "A")
        router.post(.openDocument(document))

        #expect(router.claim(for: UUID()) == .openDocument(document))
    }
}
