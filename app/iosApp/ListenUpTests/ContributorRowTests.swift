import Testing
@testable import ListenUp

/// Pins that `ContributorRow` carries `imagePath` as part of its value identity, so a sync-driven
/// photo change re-renders `PersonRow` and busts the avatar cache. (`init(_:)` from the bridged
/// `ContributorWithBookCount` needs live KMP state — pinned at the pure memberwise seam, per the
/// `SeriesDetailObserver` convention.)
@Suite("ContributorRow")
struct ContributorRowTests {
    @Test func differentImagePathProducesDifferentRow() {
        let before = ContributorRow(
            id: "c-1", name: "Brandon Sanderson", bookCount: 12,
            imagePath: "contributors/aaa.jpg"
        )
        let after = ContributorRow(
            id: "c-1", name: "Brandon Sanderson", bookCount: 12,
            imagePath: "contributors/bbb.jpg"
        )
        #expect(before != after)
    }

    @Test func differentFrameProducesDifferentRow() {
        let centred = ContributorRow(
            id: "c-1", name: "Ursula K. Le Guin", bookCount: 3, imagePath: "contributors/aaa.jpg"
        )
        let framed = ContributorRow(
            id: "c-1", name: "Ursula K. Le Guin", bookCount: 3, imagePath: "contributors/aaa.jpg",
            imageFrame: ImageFrameValue(focusX: 0.5, focusY: 0.3, zoom: 1.8)
        )
        #expect(centred.imageFrame == nil)
        #expect(centred != framed)
    }
}
