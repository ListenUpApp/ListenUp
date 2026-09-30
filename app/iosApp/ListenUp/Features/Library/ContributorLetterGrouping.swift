import Foundation
import Shared

/// Groups contributors under alphabet headers for the name-sorted list. The pure
/// `letterBuckets` core (over plain strings) carries the logic and is unit-tested;
/// `group` maps real contributors through it.
enum ContributorLetterGrouping {
    /// One letter section: the header letter and the indices (into the input) it covers.
    struct LetterBucket: Equatable {
        let letter: String
        let indices: [Int]
    }

    /// One rendered group: the header letter and its contributors, input order preserved.
    struct Group: Equatable {
        let letter: String
        let items: [ContributorRow]
    }

    /// Buckets keys by their uppercased first letter (A–Z); anything else → "#".
    /// Headers appear in first-seen order; indices within a bucket preserve input order.
    static func letterBuckets(_ keys: [String]) -> [LetterBucket] {
        var order: [String] = []
        var byLetter: [String: [Int]] = [:]
        for (index, key) in keys.enumerated() {
            let letter = headerLetter(for: key)
            if byLetter[letter] == nil { order.append(letter) }
            byLetter[letter, default: []].append(index)
        }
        return order.map { LetterBucket(letter: $0, indices: byLetter[$0] ?? []) }
    }

    /// Groups `items` by `key(item)` into letter sections.
    static func group(_ items: [ContributorRow], key: (ContributorRow) -> String) -> [Group] {
        let buckets = letterBuckets(items.map(key))
        return buckets.map { bucket in
            Group(letter: bucket.letter, items: bucket.indices.map { items[$0] })
        }
    }

    private static func headerLetter(for key: String) -> String {
        guard let first = key.first, first.isASCII, first.isLetter else { return "#" }
        return String(first).uppercased()
    }
}

// MARK: - Sections for the contributor list

extension ContributorLetterGrouping {
    /// The sections the Authors / Narrators list renders: one per letter on a name sort, and one
    /// unlettered section holding everyone, in the VM's order, on any other sort.
    static func sections(_ rows: [ContributorRow], isNameSort: Bool) -> [Group] {
        guard !rows.isEmpty else { return [] }
        return isNameSort ? group(rows, key: { $0.name }) : [Group(letter: "", items: rows)]
    }
}

/// Keeps one contributor list's sections, regrouping only when the rows or the sort actually
/// change. The shared ViewModel re-emits the whole Library state on every position save and sync
/// tick; grouping in the view's body redid an O(n) pass over every contributor on each of those
/// (2026-09-29 iOS audit, performance).
struct ContributorSectionCache {
    private(set) var rows: [ContributorRow] = []
    private(set) var isNameSort = false
    private(set) var sections: [ContributorLetterGrouping.Group] = []

    /// Regroups if `rows` or `isNameSort` differ from what's cached; returns whether it did.
    @discardableResult
    mutating func update(rows newRows: [ContributorRow], isNameSort newIsNameSort: Bool) -> Bool {
        guard newRows != rows || newIsNameSort != isNameSort || (sections.isEmpty && !newRows.isEmpty) else {
            return false
        }
        rows = newRows
        isNameSort = newIsNameSort
        sections = ContributorLetterGrouping.sections(newRows, isNameSort: newIsNameSort)
        return true
    }
}
