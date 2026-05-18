import Foundation
import os

/// Unified OSLog logging for the iOS app. Category is derived from the calling
/// file, so log lines group by source file in Console.app / Xcode.
///
/// Usage: `Log.info("…")`, `Log.error("…", error: someError)`.
enum Log {
    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.calypsan.listenup"

    static func debug(_ message: String, file: String = #fileID) {
        logger(for: file).debug("\(message, privacy: .public)")
    }

    static func info(_ message: String, file: String = #fileID) {
        logger(for: file).info("\(message, privacy: .public)")
    }

    static func warning(_ message: String, file: String = #fileID) {
        logger(for: file).warning("\(message, privacy: .public)")
    }

    static func error(_ message: String, error: Error? = nil, file: String = #fileID) {
        let log = logger(for: file)
        if let error {
            log.error("\(message, privacy: .public): \(error.localizedDescription, privacy: .public)")
        } else {
            log.error("\(message, privacy: .public)")
        }
    }

    static func fault(_ message: String, file: String = #fileID) {
        logger(for: file).fault("\(message, privacy: .public)")
    }

    /// A logger for an explicit subsystem category.
    static func forSubsystem(_ category: String) -> os.Logger {
        os.Logger(subsystem: subsystem, category: category)
    }

    private static func logger(for file: String) -> os.Logger {
        let category = file
            .split(separator: "/").last
            .map { $0.split(separator: ".").first.map(String.init) ?? String($0) } ?? "App"
        return os.Logger(subsystem: subsystem, category: category)
    }
}
