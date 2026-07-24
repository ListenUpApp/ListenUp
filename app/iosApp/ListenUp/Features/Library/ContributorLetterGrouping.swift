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
