import Foundation

/// A native, test-constructible cast member. KMP `BookContributor` is not constructible
/// in Swift tests, so grouping/collapse logic operates on this value type; the view maps
/// `BookContributor` → `CastMember` (thin untested glue, like the chapters mapping).
struct CastMember: Identifiable, Hashable {
    let id: String
    let name: String
    let roles: [String]
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
