import Foundation
import Testing
import Shared
@testable import ListenUp

@Suite("Book detail visibility")
struct BookVisibilityTests {
    private let kids = [CollectionRef(id: "c1", name: "Kids")]

    @Test func nothingRendersForAMemberAPublicBookOrAHeldOne() {
        #expect(BookVisibilityModel.from(nil) == nil)
        #expect(BookVisibilityModel.from(BookVisibilityPublic.shared) == nil)
        #expect(BookVisibilityModel.from(BookVisibilityHeld.shared) == nil)
    }

    @Test func strandedAndRestrictedMapAcross() {
        #expect(BookVisibilityModel.from(BookVisibilityStranded.shared) == .stranded)
        let kidsRow = [VisibilityCollection(id: "c1", name: "Kids")]
        let alice = HiddenFromMembers(names: ["Alice"])
        let hiddenFromAlice = BookVisibilityRestricted(collections: kids, hiddenFrom: alice)
        #expect(
            BookVisibilityModel.from(hiddenFromAlice)
                == .restricted(collections: kidsRow, hiddenFrom: .members(["Alice"]))
        )
        let seenByAll = BookVisibilityRestricted(collections: kids, hiddenFrom: HiddenFromNobody.shared)
        #expect(BookVisibilityModel.from(seenByAll) == .restricted(collections: kidsRow, hiddenFrom: .nobody))
    }

    @Test func theHeadlineNamesWhoCannotSeeItThreeAtMostUntilShowAll() {
        #expect(VisibilityCopy.headline(.members(["Alice", "Ben"]), expanded: false) == "Hidden from Alice and Ben")
        let five = ["Alice", "Dev", "Hana", "Lee", "Zoe"]
        #expect(VisibilityCopy.headline(.members(five), expanded: false) == "Hidden from Alice, Dev, Hana and 2 others")
        #expect(VisibilityCopy.headline(.members(five), expanded: true) == "Hidden from Alice, Dev, Hana, Lee and Zoe")
        #expect(VisibilityCopy.canExpand(five, expanded: false))
        #expect(!VisibilityCopy.canExpand(five, expanded: true))
        #expect(VisibilityCopy.headline(.nobody, expanded: false) == "Every member can see it")
        #expect(VisibilityCopy.headline(.everyone, expanded: false) == "Hidden from all members")
    }

    @Test func theNameListReadsNaturallyAtOneThreeAndFourNames() {
        #expect(VisibilityCopy.headline(.members(["Alice"]), expanded: false) == "Hidden from Alice")
        #expect(
            VisibilityCopy.headline(.members(["Alice", "Ben", "Cy"]), expanded: false)
                == "Hidden from Alice, Ben and Cy"
        )
        #expect(
            VisibilityCopy.headline(.members(["Alice", "Ben", "Cy", "Dev"]), expanded: false)
                == "Hidden from Alice, Ben, Cy and 1 other"
        )
    }

    @Test func theReasonIsWordedForOneCollectionOrSeveral() {
        let one = [VisibilityCollection(id: "c1", name: "Sci-Fi Club")]
        let two = one + [VisibilityCollection(id: "c2", name: "Family")]
        let alice = HiddenFromModel.members(["Alice"])
        #expect(VisibilityCopy.reason(alice, collections: one) == "Only people in Sci-Fi Club can see it.")
        #expect(
            VisibilityCopy.reason(alice, collections: two) == "Anyone in at least one of these collections can see it."
        )
        #expect(VisibilityCopy.reason(.nobody, collections: one) == "Every member is in Sci-Fi Club.")
        #expect(
            VisibilityCopy.reason(.everyone, collections: one)
                == "No member is in Sci-Fi Club yet, so only admins can see it."
        )
        #expect(
            VisibilityCopy.reason(.nobody, collections: two)
                == "Every member is in at least one of these collections."
        )
        #expect(
            VisibilityCopy.reason(.everyone, collections: two)
                == "No member is in any of these collections yet, so only admins can see it."
        )
    }
}
