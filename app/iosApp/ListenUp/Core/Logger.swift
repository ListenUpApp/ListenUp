import Foundation
import os
import Shared

/// Unified OSLog logging for the iOS app. Category is derived from the calling
/// file, so log lines group by source file in Console.app / Xcode.
///
/// Usage: `Log.info("…")`, `Log.error("…", error: someError)`.
///
/// **`message` is public; `detail` is private.** `message` is for the constant, developer-authored
/// sentence — it must never carry a token, a signed URL, a credential, or user content. Anything
/// runtime-valued and possibly sensitive goes in `detail:`, which OSLog redacts to `<private>` on
/// device and reveals only to an attached developer.
///
/// **Every line is also written to the on-device log file** the shared Kotlin core keeps (the one
/// Settings → Share logs hands over), `detail` included: a file the user chooses to share is the
/// whole point of keeping it, and `<private>` would leave it saying nothing. The rule for `detail`
/// therefore stands as it always has — redact credentials before they reach it (`UrlRedaction`).
enum Log {
    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.calypsan.listenup"

    static func debug(_ message: String, detail: String? = nil, file: String = #fileID) {
        let log = logger(for: file)
        if let detail {
            log.debug("\(message, privacy: .public) — \(detail, privacy: .private)")
        } else {
            log.debug("\(message, privacy: .public)")
        }
        persist("DEBUG", fileLine(message, detail: detail), file: file)
    }

    static func info(_ message: String, detail: String? = nil, file: String = #fileID) {
        let log = logger(for: file)
        if let detail {
            log.info("\(message, privacy: .public) — \(detail, privacy: .private)")
        } else {
            log.info("\(message, privacy: .public)")
        }
        persist("INFO", fileLine(message, detail: detail), file: file)
    }

    static func warning(_ message: String, detail: String? = nil, file: String = #fileID) {
        let log = logger(for: file)
        if let detail {
            log.warning("\(message, privacy: .public) — \(detail, privacy: .private)")
        } else {
            log.warning("\(message, privacy: .public)")
        }
        persist("WARN", fileLine(message, detail: detail), file: file)
    }

    static func error(_ message: String, detail: String? = nil, error: Error? = nil, file: String = #fileID) {
        // The error's description stays on the public channel, exactly as before; only `detail` is private.
        let described = error.map { "\(message): \($0.localizedDescription)" } ?? message
        let log = logger(for: file)
        if let detail {
            log.error("\(described, privacy: .public) — \(detail, privacy: .private)")
        } else {
            log.error("\(described, privacy: .public)")
        }
        persist("ERROR", fileLine(message, detail: detail, error: error), file: file)
    }

    static func fault(_ message: String, detail: String? = nil, file: String = #fileID) {
        let log = logger(for: file)
        if let detail {
            log.fault("\(message, privacy: .public) — \(detail, privacy: .private)")
        } else {
            log.fault("\(message, privacy: .public)")
        }
        persist("FAULT", fileLine(message, detail: detail), file: file)
    }

    /// The text a call writes to the log file: the message, the error as its type and description
    /// (a localized description alone rarely says which framework failed), then the detail.
    static func fileLine(_ message: String, detail: String?, error: Error? = nil) -> String {
        var line = message
        if let error {
            line += ": \(String(reflecting: type(of: error))): \(error.localizedDescription)"
        }
        if let detail {
            line += " — \(detail)"
        }
        return line
    }

    private static func persist(_ level: String, _ line: String, file: String) {
        ExportedKotlinPackages.com.calypsan.listenup.client.core.logging.appendNativeLogLine(
            level: level,
            category: category(for: file),
            message: line
        )
    }

    /// A logger for an explicit subsystem category.
    static func forSubsystem(_ category: String) -> os.Logger {
        os.Logger(subsystem: subsystem, category: category)
    }

    private static func logger(for file: String) -> os.Logger {
        os.Logger(subsystem: subsystem, category: category(for: file))
    }

    private static func category(for file: String) -> String {
        file
            .split(separator: "/").last
            .map { $0.split(separator: ".").first.map(String.init) ?? String($0) } ?? "App"
    }
}

/// Reduces a URL to `scheme://host/path`, dropping the query and fragment.
///
/// The server signs audio URLs with an HMAC in the query string
/// (`/api/v1/audio/{book}/{file}?u=&exp=&sig=`), so logging one verbatim writes a live
/// credential into the device log. The path alone is what diagnosis actually needs.
enum UrlRedaction {
    static func withoutQuery(_ raw: String?) -> String {
        guard let raw, let components = URLComponents(string: raw) else { return "—" }
        guard let host = components.host else { return components.path.isEmpty ? "—" : components.path }
        let scheme = components.scheme.map { "\($0)://" } ?? ""
        return "\(scheme)\(host)\(components.path)"
    }
}
