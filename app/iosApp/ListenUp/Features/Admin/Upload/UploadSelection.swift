import Foundation

/// One file the user picked, as a path the upload can open and the path the server should see.
struct UploadPick: Equatable, Sendable {
    /// Where the file sits relative to what was picked — a bare filename for loose files, or a path
    /// that begins with the picked folder's own name.
    let relPath: String
    /// The absolute filesystem path the shared `FileSource` streams from.
    let path: String
}

/// Turns a picker's URLs into `UploadPick`s — the structure the user chose, and nothing more.
///
/// The client guesses nothing about how many books a pile of files is; the server's grouper reads the
/// tags and decides. What it can do is keep the picked folder's shape, *including the folder's own
/// name*: a folder called "Rediscovering Christmas" is the strongest title signal the server gets.
/// Same two shapes as Android's picker.
///
/// Safe off the main actor: it only reads the filesystem, and every value it returns is `Sendable`.
/// The caller must already hold the picked URLs' security-scoped access.
enum UploadSelection {
    /// Loose files: each is its own filename, with no folder invented for it.
    static func files(_ urls: [URL]) -> [UploadPick] {
        urls.map { UploadPick(relPath: $0.lastPathComponent, path: $0.path) }
    }

    /// Every regular file under `root`, depth-first and sorted, each path led by the folder's name.
    ///
    /// Hidden files are skipped: Finder's `.DS_Store` and iCloud's `.name.icloud` placeholders are
    /// never part of a book, and a placeholder is not even the file's bytes.
    static func folder(_ root: URL) -> [UploadPick] {
        let rootName = root.lastPathComponent
        // Resolved on both sides: a temporary or container path may reach the walk as `/private/var`
        // while the picker said `/var`, and the prefix must match for the relative path to be right.
        let rootPath = root.resolvingSymlinksInPath().path
        guard let walk = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else { return [] }

        var picks: [UploadPick] = []
        for case let url as URL in walk {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else {
                continue
            }
            let path = url.resolvingSymlinksInPath().path
            let inside = path.hasPrefix(rootPath + "/")
                ? String(path.dropFirst(rootPath.count + 1))
                : url.lastPathComponent
            picks.append(UploadPick(relPath: "\(rootName)/\(inside)", path: path))
        }
        return picks.sorted { $0.relPath < $1.relPath }
    }
}
