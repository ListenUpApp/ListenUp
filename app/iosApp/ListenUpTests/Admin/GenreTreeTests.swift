import Testing
@testable import ListenUp

/// The tree arithmetic behind the admin categories screen is pure Swift over snapshot models, so
/// the rows the `List` shows and the moves the sheet offers are pinned here without a live
/// Kotlin ViewModel. The observer's flatten and the sheets read these exact functions.
@Suite("GenreTree")
struct GenreTreeTests {
    private let fantasy = GenreNodeModel(id: "fan", name: "Fantasy", path: "/fiction/fantasy", bookCount: 4)
    private let scifi = GenreNodeModel(id: "sf", name: "Sci-Fi", path: "/fiction/sci-fi", bookCount: 2)
    private var fiction: GenreNodeModel {
        GenreNodeModel(id: "fic", name: "Fiction", path: "/fiction", bookCount: 9, children: [fantasy, scifi])
    }
    private let history = GenreNodeModel(id: "his", name: "History", path: "/history", bookCount: 1)

    @Test func collapsedRootsShowOnlyThemselves() {
        let rows = GenreTree.visibleRows([fiction, history], expanded: [])
        #expect(rows.map(\.id) == ["fic", "his"])
        #expect(rows[0].hasChildren && !rows[0].isExpanded)
        #expect(!rows[1].hasChildren)
    }

    @Test func expandingARootRevealsItsChildrenAtTheNextDepth() {
        let rows = GenreTree.visibleRows([fiction, history], expanded: ["fic"])
        #expect(rows.map(\.id) == ["fic", "fan", "sf", "his"])
        #expect(rows.map(\.depth) == [0, 1, 1, 0])
    }

    @Test func expandableIdsAreExactlyTheNodesWithChildren() {
        #expect(GenreTree.expandableIds([fiction, history]) == ["fic"])
    }

    @Test func moveCandidatesExcludeSelfAndDescendantsButKeepPrefixSiblings() {
        let picks = [
            GenrePickModel(id: "fic", name: "Fiction", path: "/fic", bookCount: 0),
            GenrePickModel(id: "fan", name: "Fantasy", path: "/fic/fantasy", bookCount: 0),
            GenrePickModel(id: "cls", name: "Classics", path: "/fic-classics", bookCount: 0),
            GenrePickModel(id: "his", name: "History", path: "/history", bookCount: 0)
        ]
        let candidates = GenreTree.moveCandidates(all: picks, source: picks[0])
        // `/fic-classics` shares the characters of `/fic` but is a sibling, not a descendant.
        #expect(candidates.map(\.id) == ["cls", "his"])
    }

    @Test func mergeCandidatesExcludeOnlyTheSource() {
        let picks = [
            GenrePickModel(id: "fic", name: "Fiction", path: "/fic", bookCount: 0),
            GenrePickModel(id: "fan", name: "Fantasy", path: "/fic/fantasy", bookCount: 0)
        ]
        #expect(GenreTree.mergeCandidates(all: picks, source: picks[0]).map(\.id) == ["fan"])
    }
}
