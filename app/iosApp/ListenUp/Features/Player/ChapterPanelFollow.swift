@preconcurrency import Shared

/// The §3.11 follow rule for the iPad chapter panel. The shared `ChapterListFollow` decides, so the panel, Android's
/// and the web's cannot disagree; this keeps the one fact it needs — which rows are fully in view, from
/// `onScrollVisibilityChange(threshold: 1.0)` — and says how to move. Pure, so the decision is tested without a scroll
/// view.
struct ChapterPanelFollow: Equatable {
    /// What the list does about the current chapter.
    enum Move: Equatable {
        /// Scroll the current row to the list's centre; animated only when it is motion the reader can follow.
        case centre(animated: Bool)
        /// Leave the list where the reader put it.
        case hold
    }

    /// Reduce Motion is on: every move is a jump, never an animation (HIG, Motion).
    var reduceMotion = false
    /// The chapter the list last centred on or held for; nil before the first.
    private(set) var current: Int?
    private var fullyVisible: Set<Int> = []

    init(reduceMotion: Bool = false) {
        self.reduceMotion = reduceMotion
    }

    /// The row at `index` came fully into view, or left it.
    mutating func setFullyVisible(_ index: Int, _ visible: Bool) {
        if visible { fullyVisible.insert(index) } else { fullyVisible.remove(index) }
    }

    /// What to do now that `index` is the current chapter. The first is always a jump to the centre: the panel opens on
    /// your chapter, and that isn't motion the reader asked for (§9.2).
    mutating func onCurrent(_ index: Int) -> Move {
        defer { current = index }
        guard let previous = current else { return .centre(animated: false) }
        guard previous != index else { return .hold }
        let move = ChapterListFollow.shared.onCurrentChanged(previousRowFullyVisible: fullyVisible.contains(previous))
        switch move.sealedType() {
        case .recentre: return backToCurrent()
        case .hold: return .hold
        }
    }

    /// "Back to Current Chapter": the move a follow makes.
    func backToCurrent() -> Move {
        .centre(animated: !reduceMotion)
    }

    /// The way back shows exactly while the current row is not fully in view.
    var showsBackToCurrent: Bool {
        guard let current else { return false }
        return ChapterListFollow.shared.showsBackToCurrent(currentRowFullyVisible: fullyVisible.contains(current))
    }
}
