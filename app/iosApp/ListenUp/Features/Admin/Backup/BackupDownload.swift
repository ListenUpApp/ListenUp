import Foundation

/// Where a downloaded backup is staged before the admin chooses where to keep it.
///
/// Each download gets its own folder (`<temp>/<token>/<archive name>`), so two downloads never
/// collide and the archive keeps its own filename when the system file mover offers it to Files.
/// A backup can be several gigabytes: it is only ever a path here, never `Data`.
enum BackupDownloadStaging {
    /// The staging path for backup `id` under `directory` — pure, no I/O.
    /// The id's last path component is the filename (an id can never climb out of its folder), with
    /// `.zip` added when it has no extension, matching Android's `application/zip` save dialog.
    static func stagingURL(forBackupId id: String, in directory: URL, token: UUID) -> URL {
        let last = (id as NSString).lastPathComponent
        let name = (last.isEmpty || last == "." || last == "..") ? "backup" : last
        let filename = (name as NSString).pathExtension.isEmpty ? "\(name).zip" : name
        return directory
            .appending(path: token.uuidString, directoryHint: .isDirectory)
            .appending(path: filename, directoryHint: .notDirectory)
    }

    /// Creates the folder that will hold `url`.
    static func prepare(_ url: URL) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
    }

    /// Removes the staged file and its folder; harmless when the file has already been moved away.
    static func discard(_ url: URL) {
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }
}

/// What the backups screen needs to know about the one download in flight: which row is busy, and
/// which finished archive is waiting for the admin to choose where it goes. A value type so the
/// transitions are tested without a view model.
struct BackupDownloadTracker: Equatable {
    /// A finished archive, staged and waiting to be saved to Files.
    struct Ready: Equatable {
        let id: String
        let url: URL
    }

    private var inFlight: Ready?
    private(set) var ready: Ready?

    /// The id of the row whose download is running.
    var downloadingId: String? { inFlight?.id }

    /// Starts a download. False — and nothing changes — while another is running or awaiting its
    /// destination: one archive at a time keeps temporary storage bounded.
    mutating func begin(id: String, url: URL) -> Bool {
        guard inFlight == nil, ready == nil else { return false }
        inFlight = Ready(id: id, url: url)
        return true
    }

    /// The download completed: the archive is ready to be saved.
    mutating func saved() {
        ready = inFlight
        inFlight = nil
    }

    /// The download failed or was abandoned. The global error surface tells the person why; here the
    /// row just stops being busy.
    mutating func endedWithoutSaving() {
        inFlight = nil
    }

    /// The admin chose a destination or cancelled. Returns the staged file to discard, if any.
    mutating func finishExport() -> URL? {
        defer { ready = nil }
        return ready?.url
    }
}
