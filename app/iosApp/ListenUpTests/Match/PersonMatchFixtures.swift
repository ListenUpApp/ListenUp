import Foundation
import Shared
@testable import ListenUp

/// Hand-built shared states for person Match details' tests. The sources carry made-up names, as in
/// `MatchFixtures`: matching code never names a real provider.
enum PersonMatchFixtures {
    static var storefront: MetadataSource { MatchFixtures.storefront }
    static var shelfdata: MetadataSource { MatchFixtures.shelfdata }

    static func key(_ id: String) -> PersonCandidateKey {
        PersonCandidateKey(refs: [ExternalRef(provider: "shelfdata", id: id, region: nil)])
    }

    static func candidate(
        id: String = "shelfdata:P1:",
        name: String = "Ray Porter",
        shownRole: ContributorRole? = .narrator,
        knownWorks: [String] = ["Project Hail Mary", "Bobiverse"],
        worksCount: Int32? = 40,
        libraryCount: Int32 = 5,
        foundIn: [MetadataSource] = [shelfdata],
        isStrong: Bool = true,
        isBest: Bool = true,
        isCurrentLink: Bool = false,
        libraryCredits: [LibraryCredit]? = nil,
        noBooksInLibrary: Bool = false
    ) -> PersonCandidateUi {
        PersonCandidateUi(
            id: id, key: key(id), name: name, photoUrl: "https://example.com/p.jpg", shownRole: shownRole,
            knownWorks: knownWorks, worksCount: worksCount, libraryCount: libraryCount,
            foundIn: foundIn, tier: isStrong ? .strong : .maybe, isBest: isBest, isCurrentLink: isCurrentLink,
            libraryCredits: libraryCredits
                ?? (libraryCount > 0 ? [LibraryCredit(role: .narrator, bookCount: libraryCount)] : []),
            noBooksInLibrary: noBooksInLibrary
        )
    }

    static var header: PersonHeaderUi { PersonHeaderUi(name: "Ray Porter", imagePath: nil) }

    /// Ray Porter here: narrated five books and translated one, by default.
    static let rayCredits = [
        LibraryCredit(role: .narrator, bookCount: 5), LibraryCredit(role: .translator, bookCount: 1)
    ]

    static func inLibrary(
        credits: [LibraryCredit] = rayCredits, count: Int32 = 6, titles: [String] = []
    ) -> InLibraryUi {
        InLibraryUi(
            credits: credits, bookCount: count, titles: titles,
            covers: titles.enumerated().map { index, title in
                LibraryCoverUi(bookId: "b\(index)", title: title, coverPath: nil, coverHash: nil)
            }
        )
    }

    static func results(
        steps: [any PersonSearchStep] = [PersonSearchStepViaYourBooks(bookCount: 5)],
        strong: [PersonCandidateUi] = [candidate()],
        maybe: [PersonCandidateUi] = [],
        pickedKey: PersonCandidateKey? = nil
    ) -> PersonFindUiStateResults {
        PersonFindUiStateResults(
            header: header, inLibrary: inLibrary(), query: "", steps: steps,
            strong: strong, maybe: maybe, partialFailure: nil, pickedKey: pickedKey
        )
    }

    static func photo(
        state: FieldState = .fillsGap,
        currentPath: String? = nil,
        setByHand: Bool = false,
        sources: [MetadataSource] = [shelfdata],
        ticked: Bool = true
    ) -> PhotoUi {
        let options = sources.enumerated().map { index, source in
            PhotoOptionUi(optionId: "p\(index)", source: source, url: "https://example.com/p\(index).jpg")
        }
        let choice: any ImageChoice = ticked
            ? ImageChoiceCandidate(optionId: options[0].optionId)
            : ImageChoiceKeepCurrent.shared
        return PhotoUi(
            currentPath: currentPath, setByHand: setByHand, state: state, options: options, choice: choice,
            proposed: options[0]
        )
    }

    static let proposedBiography = "Ray Porter is an actor and audiobook narrator known for science fiction."

    static func biography(
        state: FieldState = .fillsGap,
        current: String? = nil,
        ticked: Bool = true,
        handEdit: HandEdit? = nil
    ) -> BiographyUi {
        let options = [
            FieldOptionUi(optionId: "b1", value: FieldValueText(text: proposedBiography), sources: [shelfdata])
        ]
        let choice: any FieldChoice = ticked ? FieldChoiceOption(optionId: "b1") : FieldChoiceKeepCurrent.shared
        return BiographyUi(
            state: state, current: current, options: options, choice: choice, proposed: options[0], handEdit: handEdit
        )
    }

    static func ready(
        photo: PhotoUi? = photo(),
        biography: BiographyUi? = biography(),
        applyBar: PersonApplySummary = PersonApplySummary(photo: true, biography: true, sources: [shelfdata]),
        applying: Bool = false,
        applyError: (any AppError)? = nil
    ) -> PersonReviewUiStateReady {
        PersonReviewUiStateReady(
            candidate: candidate(), photo: photo, biography: biography, applyBar: applyBar,
            applying: applying, applyError: applyError
        )
    }
}
