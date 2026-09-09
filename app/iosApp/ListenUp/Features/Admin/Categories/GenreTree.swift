import Foundation
import Shared

/// One genre, as Swift sees it: a value snapshot of the bridged `GenreTreeNode`, taken once at the
/// observer boundary so nothing on SwiftUI's diff path re-crosses into Kotlin (rule 8).
struct GenreNodeModel: Equatable, Identifiable {
    let id: String
    let name: String
    let path: String
    let bookCount: Int
    let children: [GenreNodeModel]

    init(id: String, name: String, path: String, bookCount: Int, children: [GenreNodeModel] = []) {
        self.id = id
        self.name = name
        self.path = path
        self.bookCount = bookCount
        self.children = children
    }

    init(from node: GenreTreeNode) {
        self.init(
            id: node.genre.id,
            name: node.genre.name,
            path: node.genre.path,
            bookCount: Int(node.genre.bookCount),
            children: Array(node.children).map(GenreNodeModel.init(from:))
        )
    }
}

/// One visible row of the tree — what the `List` renders, nothing more.
struct GenreRowModel: Equatable, Identifiable {
    let id: String
    let name: String
    let path: String
    let depth: Int
    let bookCount: Int
    let hasChildren: Bool
    let isExpanded: Bool
}

/// A genre as a pick in the move and merge sheets.
struct GenrePickModel: Equatable, Identifiable {
    let id: String
    let name: String
    let path: String
    let bookCount: Int

    init(id: String, name: String, path: String, bookCount: Int) {
        self.id = id
        self.name = name
        self.path = path
        self.bookCount = bookCount
    }

    init(from genre: Genre) {
        self.init(id: genre.id, name: genre.name, path: genre.path, bookCount: Int(genre.bookCount))
    }
}

/// Pure tree arithmetic over the snapshot models. Mirrors the Kotlin `AdminCategoriesViewModel`'s
/// tree and `genreMoveCandidates` exactly, so the two platforms cannot disagree about which rows
/// are visible or which moves would create a cycle.
enum GenreTree {
    /// Depth-first walk that descends only into expanded nodes.
    static func visibleRows(_ roots: [GenreNodeModel], expanded: Set<String>) -> [GenreRowModel] {
        var rows: [GenreRowModel] = []
        func walk(_ node: GenreNodeModel, depth: Int) {
            let isExpanded = expanded.contains(node.id)
            rows.append(
                GenreRowModel(
                    id: node.id,
                    name: node.name,
                    path: node.path,
                    depth: depth,
                    bookCount: node.bookCount,
                    hasChildren: !node.children.isEmpty,
                    isExpanded: isExpanded
                )
            )
            if isExpanded {
                node.children.forEach { walk($0, depth: depth + 1) }
            }
        }
        roots.forEach { walk($0, depth: 0) }
        return rows
    }

    /// Ids of every node that has children — the set "expand all" has to cover.
    static func expandableIds(_ roots: [GenreNodeModel]) -> Set<String> {
        var ids: Set<String> = []
        func walk(_ node: GenreNodeModel) {
            if !node.children.isEmpty { ids.insert(node.id) }
            node.children.forEach(walk)
        }
        roots.forEach(walk)
        return ids
    }

    /// Valid new parents for `source`: every other genre that is not `source` itself and not one of
    /// its descendants (a cycle). Descendants are detected by the materialized-path prefix
    /// `source.path + "/"` — the trailing slash keeps a sibling like `/fic-classics` from matching
    /// `/fic`. Top level is offered by the sheet separately and is not in this list.
    static func moveCandidates(all: [GenrePickModel], source: GenrePickModel) -> [GenrePickModel] {
        all.filter { $0.id != source.id && !$0.path.hasPrefix(source.path + "/") }
    }

    /// Every other genre; merging into a descendant is fine, the source disappears.
    static func mergeCandidates(all: [GenrePickModel], source: GenrePickModel) -> [GenrePickModel] {
        all.filter { $0.id != source.id }
    }
}
