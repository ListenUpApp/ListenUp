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

    @Test func aRowIsReadRankFirst() async throws {
        let row = LeaderboardRow(
            id: "u1", rank: 1, displayName: "Maya Chen", initials: "MC", value: "14h 20m", isCurrentUser: false
        )
        let hosted = await host(LeaderRow(row: row), height: 120)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Rank 1, Maya Chen, 14h 20m") != nil, "\(hosted.tree)")
    }

    @Test func yourRowReadsYou() async throws {
        let row = LeaderboardRow(
            id: "me", rank: 2, displayName: "Simon Hull", initials: "SH", value: "8h 12m", isCurrentUser: true
        )
        let hosted = await host(LeaderRow(row: row), height: 120)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Rank 2, You, 8h 12m") != nil, "\(hosted.tree)")
        #expect(hosted.stops(labelContaining: "Simon Hull").isEmpty, "\(hosted.tree)")
    }

    @Test func activityAvatarIsNamedForTheProfile() async throws {
        let item = ActivityRowItem(
            id: "a1", userId: "u1", who: "Maya Chen", predicate: "finished", highlight: "Piranesi",
            suffix: " by Susanna Clarke", bookId: "b1", occurredAt: now.addingTimeInterval(-7_200)
        )
        let hosted = await host(ActivityRow(item: item, now: now), height: 120)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Maya Chen's profile") != nil, "\(hosted.tree)")
        let sentence = hosted.stops(labelContaining: "Maya Chen finished Piranesi by Susanna Clarke")
        #expect(!sentence.isEmpty, "\(hosted.tree)")
    }

    @Test func yourActivityAvatarIsYourProfile() async throws {
        let item = ActivityRowItem(
            id: "a1", userId: "me", who: "You", predicate: "finished", highlight: "Piranesi",
            bookId: "b1", occurredAt: now.addingTimeInterval(-7_200), isYou: true, actorName: "Simon Hull"
        )
        let hosted = await host(ActivityRow(item: item, now: now), height: 120)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Your profile") != nil, "\(hosted.tree)")
    }
}
