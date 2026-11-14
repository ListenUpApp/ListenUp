import Foundation
import Testing
import Shared
@testable import ListenUp

/// Everyone else on a chapter shows as a stack of avatars on its row, and VoiceOver hears them as one sentence:
/// whoever is listening now first, then the rest, two names a clause and a count after.
@Suite("Book Detail chapter readers")
struct ChapterReadersTests {
    private func face(_ name: String, live: Bool = false) -> ChapterReaderFace {
        ChapterReaderFace(userId: name.lowercased(), displayName: "\(name) Vance", isLive: live)
    }

    private func stack(_ faces: [ChapterReaderFace], place: ReadersPlace = .ThisChapter) -> ChapterReaderStack {
        ChapterReaderStack(ChapterReaders(place: place, readers: faces))
    }

    // MARK: - Faces and the "+N" disc

    @Test func upToThreeReadersAllShowTheirFaces() {
        let three = stack([face("Priya"), face("Tom"), face("Sam")])
        #expect(three.faces.map(\.id) == ["priya", "tom", "sam"])
        #expect(three.overflowCount == 0)
    }

    @Test func fourOrMoreShowTwoFacesBesideTheCount() {
        let five = stack([face("Priya"), face("Tom"), face("Sam"), face("Lee"), face("Ana")])
        #expect(five.faces.map(\.id) == ["priya", "tom"])
        #expect(five.overflowCount == 3)
    }

    @Test func aFaceCarriesItsNameAndWhetherItIsLive() {
        let maya = stack([face("Maya", live: true)])
        #expect(maya.faces == [ChapterReaderFaceRow(id: "maya", displayName: "Maya Vance", isLive: true)])
    }

    // MARK: - The sentence

    @Test func oneReaderIsOnThisChapterByFirstName() {
        #expect(stack([face("Jonah")]).sentence == "Jonah is on this chapter.")
    }

    @Test func twoThreeAndManyShortenTheWayTheHeroLineDoes() {
        #expect(stack([face("Priya"), face("Tom")]).sentence == "Priya and Tom are on this chapter.")
        #expect(stack([face("Priya"), face("Tom"), face("Sam")]).sentence
                == "Priya, Tom and 1 other are on this chapter.")
        #expect(stack([face("Priya"), face("Tom"), face("Sam"), face("Lee")]).sentence
                == "Priya, Tom and 2 others are on this chapter.")
    }

    @Test func whoeverIsListeningNowComesFirst() {
        let row = stack([face("Maya", live: true), face("Priya"), face("Tom"), face("Sam"), face("Lee")])
        #expect(row.sentence == "Maya is listening now. Priya, Tom and 2 others are on this chapter.")
    }

    @Test func onlyListenersSayOnlyThatTheyAreListening() {
        let row = stack([face("Maya", live: true), face("Lee", live: true)])
        #expect(row.sentence == "Maya and Lee are listening now.")
    }

    @Test func eachPlaceHasItsOwnWording() {
        #expect(stack([face("Tom")], place: .ThisPart).sentence == "Tom is in this part.")
        #expect(stack([face("Maya"), face("Tom")], place: .FurtherOn).sentence
                == "Maya and Tom are on chapters further on.")
        #expect(stack([face("Tom")], place: .Earlier).sentence == "Tom is on an earlier chapter.")
        #expect(stack([face("Tom")], place: .OtherChapters).sentence == "Tom is on another chapter.")
    }

    // MARK: - The lookup

    @Test func nobodyIsAnywhereByDefault() {
        let none = ChapterReadersLookup.none
        #expect(none.onChapter("c1") == nil)
        #expect(none.inPart("c1") == nil)
        #expect(none.beyond(["c1"]) == nil)
    }
}

/// The glow behind a live reader breathes on one clock: a 1.6 s swell and fade, a 0.4 s rest, every glow in step.
@Suite("Chapter readers breathing glow")
struct BreathingGlowTests {
    @Test func aBreathStartsSmallAndFaint() {
        let start = BreathingGlow.frame(atPhase: 0)
        #expect(start.scale == 1)
        #expect(abs(start.opacity - 0.45) < 0.0001)
    }

    @Test func itRestsFullyOutAndInvisibleForTheLastFourTenths() {
        for phase in [1.6, 1.8, 1.99] {
            #expect(BreathingGlow.frame(atPhase: phase) == BreathingGlow.Frame(scale: 1.7, opacity: 0))
        }
    }

    @Test func theSwellDeceleratesAndOnlyEverGrows() {
        let frames = stride(from: 0.0, to: 1.6, by: 0.1).map { BreathingGlow.frame(atPhase: $0) }
        #expect(zip(frames, frames.dropFirst()).allSatisfy { $0.scale <= $1.scale && $0.opacity >= $1.opacity })
        // Emphasized decelerate: most of the swell is done in the first quarter of the breath.
        #expect(BreathingGlow.frame(atPhase: 0.4).scale > 1.35)
    }

    @Test func everyCycleIsTheSameSoEveryGlowIsInStep() {
        #expect(BreathingGlow.frame(atPhase: 0.5) == BreathingGlow.frame(atPhase: 4.5))
        let onTheClock = BreathingGlow.frame(at: Date(timeIntervalSinceReferenceDate: 1_000.25))
        let intoTheBreath = BreathingGlow.frame(atPhase: 0.25)
        #expect(onTheClock == intoTheBreath)
    }

    @Test func reduceMotionHoldsAFaintStillGlow() {
        #expect(BreathingGlow.still == BreathingGlow.Frame(scale: 1.35, opacity: 0.22))
    }
}
