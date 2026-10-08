import Foundation
import Shared

// Native value types for person Match details, mapped once at the observer boundary (iosApp rule 8): the
// views, their `ForEach`es and the accessibility harness only ever see these, never a bridged Kotlin object.
// Everything a row says is already a sentence here, so a view is layout and nothing else.

/// The two roles a person is matched in: the segments of As author | As narrator.
enum PersonMatchRole: String, CaseIterable, Hashable, Identifiable {
    case author, narrator

    var id: String { rawValue }

    /// The matchable role a shared role names, or nil for the roles matching never searches.
    init?(_ role: ContributorRole) {
        switch role {
        case .author: self = .author
        case .narrator: self = .narrator
        default: return nil
        }
    }

    var contributorRole: ContributorRole {
        switch self {
        case .author: .author
        case .narrator: .narrator
        }
    }

    /// "As author", "As narrator".
    var segmentTitle: String {
        switch self {
        case .author: String(localized: "match.as_author")
        case .narrator: String(localized: "match.as_narrator")
        }
    }
}

/// One of the person's books in the Your-library strip.
struct PersonLibraryCover: Identifiable, Equatable {
    let id: String
    let title: String
    let coverPath: String?
    let coverHash: String?
}

/// "Wrote 3 books in your library: Project Hail Mary, The Martian, Artemis", with up to three covers.
struct PersonLibraryStrip: Equatable {
    let line: String
    let covers: [PersonLibraryCover]
}

/// One person Find found, as a row says them.
struct PersonCandidateRow: Identifiable, Equatable {
    let id: String
    let name: String
    let photoURL: String?
    let isStrong: Bool
    let isBest: Bool
    let isCurrentLink: Bool
    /// "Narrator · Project Hail Mary, Bobiverse", "Author · 1 book · Not a narrator".
    let roleLine: String
    /// "Narrated 5 books in your library", or "No books in your library".
    let libraryLine: String
    /// "Different role" — set only on a person the sources credit in another role.
    let differentRole: String?
    /// "Audible · Hardcover".
    let sourcesLine: String
    /// "Audible and Hardcover" — for sentences ("Narrator · from Hardcover").
    let sourcesList: String
    /// "Narrator", or nil when the sources name no role matching searches.
    let shownRole: String?
    /// The whole row in one sentence: "Ray Porter. Author, 1 book. Not a narrator. Different role. Hardcover."
    let accessibilityLabel: String
}

/// People to choose from.
struct PersonResults: Equatable {
    let strong: [PersonCandidateRow]
    let maybe: [PersonCandidateRow]
    let partial: MatchPartialBanner?
    let pickedId: String?

    var all: [PersonCandidateRow] { strong + maybe }
}

/// "No source has a profile for this narrator", and Edit by Hand.
struct PersonNoProfiles: Equatable {
    let title: String
    let message: String
    let editTitle: String
}

/// The person Find step.
enum PersonFindPhase: Equatable {
    /// A search is running; the last results for this role stay on screen.
    case searching(previous: PersonResults?)
    case results(PersonResults)
    case noProfiles(PersonNoProfiles)
    case failed(MatchFailure)

    /// The rows on screen in this phase.
    var results: PersonResults? {
        switch self {
        case .searching(let previous): previous
        case .results(let results): results
        case .noProfiles, .failed: nil
        }
    }
}

/// Everything person Find shows.
struct PersonFind: Equatable {
    let role: PersonMatchRole
    /// The person's name from this device, or "" for the instant before it answers.
    let name: String
    /// "Ray Porter · narrator".
    let subtitle: String
    /// "Search for a narrator".
    let searchPrompt: String
    let query: String
    let library: PersonLibraryStrip?
    /// "Audible has no narrator profiles, so this search uses Hardcover."
    let coverageNote: String?
    /// "Started from the 5 books Ray Porter narrates in your library."
    let stepsLine: String?
    let phase: PersonFindPhase

    static let initial = PersonFind(
        role: .author, name: "", subtitle: "", searchPrompt: String(localized: "match.search_person_label"),
        query: "", library: nil, coverageNote: nil, stepsLine: nil, phase: .searching(previous: nil)
    )
}

// MARK: - Review

/// The photo decision: a tick, Yours → Proposed, and a source switch with Keep current.
struct PersonPhotoSection: Equatable {
    /// "Changes", "Fills a gap", "You edited this" beside the section's heading.
    let stateTitle: String
    let isTicked: Bool
    /// "from Hardcover" — the source of the photo shown as Proposed.
    let proposedFrom: String
    /// The stored path of the person's own photo, or nil when they have none.
    let currentPath: String?
    /// "Your photo", or "Yours · no photo".
    let yoursCaption: String
    let proposedURL: String
    /// "Photo from Hardcover".
    let proposedLabel: String
    let segments: [MatchSourceSegment]
    let selectedSegment: MatchSourceSelection
    let switchStyle: MatchSourceSwitchStyle
    /// "You set this photo by hand. Kept unless you choose another." when set by hand.
    let setByHandNote: String?
}

/// The biography decision: a tick, Yours → Proposed with Read All, and a source switch with Keep yours.
struct PersonBiographySection: Equatable {
    let stateTitle: String
    /// "Your biography already matches." — then there is nothing to tick.
    let alreadySame: String?
    let isTicked: Bool
    let proposedFrom: String
    let values: MatchValues
    let isEdited: Bool
    /// "Edited by you, 12 Sep. Kept unless you tick it."
    let editedNote: String?
    let segments: [MatchSourceSegment]
    let selectedSegment: MatchSourceSelection
    let switchStyle: MatchSourceSwitchStyle
}

/// The person Review is about, as its header says them.
struct PersonReviewHeader: Equatable {
    let name: String
    let photoURL: String?
    let isStrong: Bool
    let isBest: Bool
    let isCurrentLink: Bool
    /// "Narrator · from Hardcover".
    let roleLine: String
    let libraryLine: String
}

/// Everything person Review shows for one candidate.
struct PersonReview: Equatable {
    let candidateId: String
    let header: PersonReviewHeader
    /// "Photo and biography · from Hardcover", or "Nothing selected".
    let whatWillChange: String
    let photo: PersonPhotoSection?
    let biography: PersonBiographySection?
    let applyBar: MatchApplyBar
}

/// The person Review step.
enum PersonReviewPhase: Equatable {
    case noneChosen
    case loading(candidateId: String, name: String)
    case failed(candidateId: String, name: String, message: String)
    case ready(PersonReview)

    /// The candidate this phase is about, if any.
    var candidateId: String? {
        switch self {
        case .noneChosen: nil
        case .loading(let id, _), .failed(let id, _, _): id
        case .ready(let review): review.candidateId
        }
    }
}
