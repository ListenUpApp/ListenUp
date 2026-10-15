import Foundation
import Shared

/// A native cast member, projected once from the bridged `BookContributor` at the observer boundary
/// (iosApp rule 8), so the hero lines and the Cast & Credits rows never re-read Kotlin.
struct CastMember: Identifiable, Hashable {
    let id: String
    let name: String
    let roles: [String]
    /// The contributor's photo path; nil shows their initials.
    let imagePath: String?
    /// How the photo sits in its circle; nil is the centre crop.
    let imageFrame: ImageFrameValue?

    init(id: String, name: String, roles: [String], imagePath: String? = nil, imageFrame: ImageFrameValue? = nil) {
        self.id = id
        self.name = name
        self.roles = roles
        self.imagePath = imagePath
        self.imageFrame = imageFrame
    }

    /// Reads each bridged property of `contributor` once.
    init(_ contributor: BookContributor) {
        self.init(
            id: contributor.id,
            name: contributor.name,
            roles: Array(contributor.roles),
            imagePath: contributor.imagePath,
            imageFrame: ImageFrameValue(contributor.imageFrame)
        )
    }
}

/// A role group in the Cast & Credits sheet.
enum CastGroupKind: Hashable {
    case authors
    case narrators
    case production
}

struct CastGroup: Identifiable, Hashable {
    var id: CastGroupKind { kind }
    let kind: CastGroupKind
    let members: [CastMember]
}

/// The author/narrator/production groups, in display order, omitting any empty group.
/// Production = everyone in `all` whose roles are neither author nor narrator
/// (case-insensitive), deduped by id.
func castGroups(authors: [CastMember], narrators: [CastMember], all: [CastMember]) -> [CastGroup] {
    func isAuthorOrNarrator(_ m: CastMember) -> Bool {
        m.roles.contains { role in
            let r = role.lowercased()
            return r == "author" || r == "narrator"
        }
    }
    var seen = Set<String>()
    let production = all.filter { !isAuthorOrNarrator($0) }.filter { seen.insert($0.id).inserted }

    var groups: [CastGroup] = []
    if !authors.isEmpty { groups.append(CastGroup(kind: .authors, members: authors)) }
    if !narrators.isEmpty { groups.append(CastGroup(kind: .narrators, members: narrators)) }
    if !production.isEmpty { groups.append(CastGroup(kind: .production, members: production)) }
    return groups
}

/// "{first} & {n} others" when `names.count` exceeds `limit`, else `nil`
/// (callers render inline chips at or below the limit).
func collapsedContributorSummary(names: [String], limit: Int) -> String? {
    guard names.count > limit, let first = names.first else { return nil }
    return String(format: String(localized: "book.detail_contributors_overflow"), first, names.count - 1)
}
