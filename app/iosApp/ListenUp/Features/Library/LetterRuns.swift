import Shared

/// One shared `LetterSection` — a run of a sorted Library list filed under one index letter —
/// bridged to Swift `Int`s once per content revision. Headers and the section index both read these,
/// so iOS files every book, series and person exactly where Android and web do.
struct LetterRun: Equatable {
    let letter: String
    let firstIndex: Int
    let count: Int

    init(letter: String, firstIndex: Int, count: Int) {
        self.letter = letter
        self.firstIndex = firstIndex
        self.count = count
    }

    init(_ section: LetterSection) {
        self.init(letter: section.letter, firstIndex: Int(section.firstIndex), count: Int(section.count))
    }

    /// One run's slice of the list, under its letter. `id` is unique even when a letter recurs.
    struct Section<Item>: Identifiable {
        let id: String
        let letter: String
        let items: [Item]
    }

    /// The list cut into one section per run; with no runs (a sort with no index), one unlabelled
    /// section holding everything; with no items, nothing.
    static func sections<Item>(of items: [Item], runs: [LetterRun]) -> [Section<Item>] {
        guard !items.isEmpty else { return [] }
        guard !runs.isEmpty else { return [Section(id: "section-all", letter: "", items: items)] }
        return runs.enumerated().map { ordinal, run in
            let start = min(run.firstIndex, items.count)
            let end = min(run.firstIndex + run.count, items.count)
            return Section(id: "section-\(ordinal)", letter: run.letter, items: Array(items[start ..< end]))
        }
    }

    /// The index's letters: each once, in list order (so a descending sort runs Z to A).
    static func railLetters(_ runs: [LetterRun]) -> [String] {
        var seen = Set<String>()
        return runs.compactMap { seen.insert($0.letter).inserted ? $0.letter : nil }
    }

    /// Where an index letter jumps: the section of the first run carrying it.
    static func sectionId(forLetter letter: String, in runs: [LetterRun]) -> String? {
        runs.firstIndex { $0.letter == letter }.map { "section-\($0)" }
    }
}
