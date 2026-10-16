import SwiftUI
import Testing
@testable import ListenUp

/// What VoiceOver hears on Discover: a listener card is one book stop named book-first plus one
/// person stop; Shuffle says what it does; a leaderboard row is read rank first and says You on your
/// own row; an activity avatar is named for the profile it opens (spec §8.2).
@MainActor
@Suite("Discover accessibility", .serialized)
struct DiscoverAccessibilityTests {
    private let now = Date(timeIntervalSince1970: 1_000_000_000)

    private func host<Content: View>(_ content: Content, height: CGFloat = 400) async -> HostedView {
        await HostedView(NavigationStack { content }, size: CGSize(width: 393, height: height))
    }

    private func listener(isLive: Bool) -> CurrentlyListeningRow {
        CurrentlyListeningRow(
            id: "u1:b1", userId: "u1", displayName: "Maya Chen", initials: "MC", bookId: "b1",
            title: "Mistborn", author: "Brandon Sanderson", coverPath: nil, coverHash: nil,
            isLive: isLive, lastActiveAt: now
        )
    }

    @Test func aListenerCardIsOneBookStopAndOneProfileStop() async throws {
        let hosted = await host(ListenerCard(row: listener(isLive: true), width: 150, selection: nil, now: now))
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Mistborn, Maya Chen is listening now") != nil, "\(hosted.tree)")
        let person = hosted.stops.contains { $0.label.hasPrefix("Maya Chen") && $0.label.contains("Listening now") }
        #expect(person, "\(hosted.tree)")
    }

    @Test func shuffleSaysWhatItDoes() async throws {
        let pick = DiscoverBook(
            id: "p1", title: "Mistborn", author: "Brandon Sanderson", coverPath: nil, coverHash: nil
        )
        let section = SomethingNewSection(
            phase: .ready([pick]), cardWidth: 150, horizontalInset: 20, isRegular: false, selection: nil, onShuffle: {}
        )
        let hosted = await host(section)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Shuffle: draw ten different books") != nil, "\(hosted.tree)")
    }
}
