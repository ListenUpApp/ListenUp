import Foundation
import Shared

/// The on-device log files the shared Kotlin core writes (`FileLogSink`, under Documents/logs):
/// what Settings → Share logs hands to the share sheet.
enum LogFiles {
    /// The files worth sharing, oldest first — empty until something has been logged.
    static func shareable() -> [URL] {
        urls(fromPaths: ExportedKotlinPackages.com.calypsan.listenup.client.core.logging.shareableLogFilePaths())
    }

    /// File URLs for `paths`, order kept, so a receiver concatenating them reads chronologically.
    static func urls(fromPaths paths: [String]) -> [URL] {
        paths.map { URL(fileURLWithPath: $0) }
    }
}
