import Testing
import UIKit
@testable import ListenUp

/// A tapped quick action waits for the session to settle, plays when signed in, and is dropped (the
/// app just opens) when signed out or when the item is not one of ours.
@MainActor
@Suite("QuickActionRouter")
struct QuickActionRouterTests {
    private let bundleID = "com.example.listenup"

    private func playItem(_ bookId: String) -> UIApplicationShortcutItem {
        RecentBookQuickActions.items(
            for: [RecentBook(id: bookId, title: "T", author: "A")],
            bundleIdentifier: bundleID
        )[0]
    }

    @Test func aTapHoldsTheBookUntilTheAuthStateIsKnown() {
        let router = QuickActionRouter(bundleIdentifier: bundleID)

        let handled = router.receive(playItem("book-1"))

        #expect(handled)
        #expect(router.pendingBookId == "book-1")
        #expect(QuickActionRouter.disposition(pendingBookId: "book-1", authState: .initializing) == .wait)
        #expect(QuickActionRouter.disposition(pendingBookId: "book-1", authState: .checkingServer) == .wait)
    }

    @Test func signedInPlaysTheBook() {
        let disposition = QuickActionRouter.disposition(pendingBookId: "book-1", authState: .authenticated)
        #expect(disposition == .play(bookId: "book-1"))
    }

    /// Credentials lapsed but the library is intact: playback still works offline, so it plays.
    @Test func aLapsedSessionStillPlaysTheBook() {
        let disposition = QuickActionRouter.disposition(pendingBookId: "book-1", authState: .sessionLapsed)
        #expect(disposition == .play(bookId: "book-1"))
    }

    @Test func signedOutDropsTheTapSoItCannotPlayAfterALaterLogin() {
        for state in [AuthStateKind.needsServerUrl, .needsSetup, .needsLogin, .pendingApproval] {
            #expect(QuickActionRouter.disposition(pendingBookId: "book-1", authState: state) == .drop)
        }
    }

    @Test func nothingPendingNeedsNothing() {
        #expect(QuickActionRouter.disposition(pendingBookId: nil, authState: .authenticated) == .wait)
    }

    @Test func anItemThatIsNotOursIsNotHandledAndHoldsNothing() {
        let router = QuickActionRouter(bundleIdentifier: bundleID)
        let foreign = UIApplicationShortcutItem(type: "other.type", localizedTitle: "X")

        #expect(router.receive(foreign) == false)
        #expect(router.pendingBookId == nil)
    }

    @Test func resolvingPlaysThroughThePlaybackSeamAndClearsThePendingBook() {
        let router = QuickActionRouter(bundleIdentifier: bundleID)
        let playback = FakePlaybackController()
        _ = router.receive(playItem("book-9"))

        router.resolve(authState: .authenticated, playback: playback)

        #expect(playback.playedBookId == "book-9")
        #expect(router.pendingBookId == nil)
    }

    @Test func resolvingWhileSignedOutPlaysNothingAndClearsThePendingBook() {
        let router = QuickActionRouter(bundleIdentifier: bundleID)
        let playback = FakePlaybackController()
        _ = router.receive(playItem("book-9"))

        router.resolve(authState: .needsLogin, playback: playback)

        #expect(playback.playedBookId == nil)
        #expect(router.pendingBookId == nil)
    }

    @Test func resolvingBeforeTheAuthStateIsKnownKeepsTheBookPending() {
        let router = QuickActionRouter(bundleIdentifier: bundleID)
        let playback = FakePlaybackController()
        _ = router.receive(playItem("book-9"))

        router.resolve(authState: .initializing, playback: playback)

        #expect(playback.playedBookId == nil)
        #expect(router.pendingBookId == "book-9")
    }
}
