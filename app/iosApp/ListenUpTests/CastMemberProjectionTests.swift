import ListenupContract
import Shared
import Testing
@testable import ListenUp

/// A book credit carries the contributor's framed photo, projected once from the bridged `BookContributor`
/// so the Cast & Credits rows can draw it without re-reading Kotlin.
@Suite("CastMember projection")
struct CastMemberProjectionTests {
    /// Built across Swift Export, which has no default arguments: every parameter is passed.
    private func contributor(imagePath: String?, imageFrame: ImageFrame?) -> BookContributor {
        BookContributor(
            id: "c-1",
            name: "Ursula K. Le Guin",
            creditedAs: nil,
            roles: ["author", "translator"],
            imagePath: imagePath,
            imageFrame: imageFrame
        )
    }

    @Test func aCreditCarriesThePhotoAndItsFrame() {
        let member = CastMember(contributor(
            imagePath: "contributors/abc.jpg",
            imageFrame: ImageFrame(focusX: 0.45, focusY: 0.3, zoom: 2)
        ))
        #expect(member.id == "c-1")
        #expect(member.name == "Ursula K. Le Guin")
        #expect(member.roles == ["author", "translator"])
        #expect(member.imagePath == "contributors/abc.jpg")
        #expect(member.imageFrame == ImageFrameValue(focusX: 0.45, focusY: 0.3, zoom: 2))
    }

    @Test func aCreditWithoutAPhotoFallsBackToInitials() {
        let member = CastMember(contributor(imagePath: nil, imageFrame: nil))
        #expect(member.imagePath == nil)
        #expect(member.imageFrame == nil)
    }

    @Test func aPhotoWithoutAFrameShowsTheCentreCrop() {
        let member = CastMember(contributor(imagePath: "contributors/abc.jpg", imageFrame: nil))
        #expect(member.imagePath == "contributors/abc.jpg")
        #expect(member.imageFrame == nil)
    }
}
