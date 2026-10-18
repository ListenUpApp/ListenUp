/// Discover's sections in reading order (spec §2.8), and how a regular-width window pairs them (§3.7):
/// people first, then something new, the community record, others' shelves, and what was added last.
enum DiscoverSection: Hashable, CaseIterable {
    case listeningNow, somethingNew, leaderboard, activity, shelvesByOthers, recentlyAdded

    static let compactOrder: [Self] = [
        .listeningNow, .somethingNew, .leaderboard, .activity, .shelvesByOthers, .recentlyAdded
    ]

    static let regularRows: [[Self]] = [
        [.listeningNow], [.somethingNew], [.leaderboard, .activity], [.shelvesByOthers], [.recentlyAdded]
    ]
}
