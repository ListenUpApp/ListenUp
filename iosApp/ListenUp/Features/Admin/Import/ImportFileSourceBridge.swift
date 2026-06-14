import Foundation
@preconcurrency import Shared
import UniformTypeIdentifiers

/// Bridges a picked backup file URL into the shared `FileSource` the import wizard consumes.
///
/// `.fileImporter` (and `UIDocumentPickerViewController`) hand back a **security-scoped** URL: we
/// must bracket the read with `startAccessingSecurityScopedResource()` /
/// `stopAccessing…`. We read the whole file into `Data`, bridge it to a Kotlin `ByteArray` via
/// the existing bulk-`memcpy` helper, and wrap it in `AppleFileSource` — which serves a fresh
/// `ByteReadChannel` per `openChannel()` so the multipart uploader can re-read the body on retry.
///
/// Reading the file fully into memory is acceptable at ListenUp's self-hosted scale (ABS backups
/// are a SQLite dump plus metadata, typically single-digit MB). If backups grow large enough to
/// matter, swap `AppleFileSource`'s backing for an `NSInputStream` adapter — the `FileSource`
/// contract already permits true streaming.
enum ImportFileSourceBridge {
    /// The uniform types `.fileImporter` should allow. ABS exports use the `.audiobookshelf`
    /// extension (an opaque zip); we also accept plain archives as a fallback for hand-renamed
    /// exports. `UTType(filenameExtension:)` returns nil for the custom extension on systems that
    /// don't know it, so we fall back to `.data` to keep the picker permissive.
    static var allowedContentTypes: [UTType] {
        var types: [UTType] = []
        if let absType = UTType(filenameExtension: "audiobookshelf") { types.append(absType) }
        types.append(contentsOf: [.zip, .gzip, .archive, .data])
        return types
    }

    /// Read `url` into a `FileSource`, or throw `ImportFilePickError` on failure.
    static func makeFileSource(from url: URL) throws -> FileSource {
        let didScope = url.startAccessingSecurityScopedResource()
        defer { if didScope { url.stopAccessingSecurityScopedResource() } }

        let data: Data
        do {
            data = try Data(contentsOf: url, options: .mappedIfSafe)
        } catch {
            throw ImportFilePickError.unreadable
        }

        guard !data.isEmpty else { throw ImportFilePickError.empty }

        return AppleFileSource(bytes: data.toKotlinByteArray(), filename: url.lastPathComponent)
    }
}

/// A failure while turning a picked file into a `FileSource`. Maps to a user-facing message at
/// the wizard boundary.
enum ImportFilePickError: Error, Equatable {
    case unreadable
    case empty

    var message: String {
        switch self {
        case .unreadable: String(localized: "import.upload_failed")
        case .empty: String(localized: "import.upload_failed")
        }
    }
}
