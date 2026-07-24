import Foundation
import Shared
import UniformTypeIdentifiers

/// Bridges a picked backup file URL into the shared `FileSource` the import wizard consumes.
///
/// `.fileImporter` (and `UIDocumentPickerViewController`) hand back a **security-scoped** URL: we
/// must bracket the read with `startAccessingSecurityScopedResource()` /
/// `stopAccessing…`. [readData] does that read off the main thread (`Data` is `Sendable`, so it
/// crosses back safely); [makeFileSource] then bridges those bytes to a Kotlin `ByteArray` via the
/// bulk-`memcpy` helper and wraps them in the shared `ByteArrayFileSource`, which serves a fresh
/// `ByteReadChannel` per `openChannel()` so the multipart uploader can re-read the body on retry.
/// The split matters because the Kotlin `FileSource` is not `Sendable` and must not cross an actor
/// boundary — only the `Sendable` `Data` does.
///
/// Reading the file fully into memory is acceptable at ListenUp's self-hosted scale (ABS backups
/// are a SQLite dump plus metadata, typically single-digit MB).
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

    /// Read the security-scoped `url` fully into memory, or throw `ImportFilePickError`. Safe to
    /// call off the main actor; the returned `Data` is self-contained (not memory-mapped), so it
    /// stays valid after the security scope is released.
    static func readData(from url: URL) throws -> Data {
        let didScope = url.startAccessingSecurityScopedResource()
        defer { if didScope { url.stopAccessingSecurityScopedResource() } }

        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw ImportFilePickError.unreadable
        }

        guard !data.isEmpty else { throw ImportFilePickError.empty }
        return data
    }

    /// Wrap already-read bytes in the shared `FileSource`. The Kotlin `ByteArray` bridge and
    /// `FileSource` are non-`Sendable`, so build them on the actor that consumes the source.
    static func makeFileSource(data: Data, filename: String) -> FileSource {
        ExportedKotlinPackages.com.calypsan.listenup.client.core.fileSourceOf(
            bytes: data.toKotlinByteArray(),
            filename: filename
        )
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
