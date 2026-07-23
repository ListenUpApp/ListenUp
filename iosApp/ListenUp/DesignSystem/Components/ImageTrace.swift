import Foundation

/// Debug-build tracing for the image pipeline (the iOS real-time-refresh hunt).
///
/// Prints `IMGTRACE …` lines to Xcode's debug console at the decisive boundaries:
/// request build (inputs, resolved base URL, chosen branch + cache key), auth-header
/// attach, and the Nuke load outcome. One reproduction should print exactly where the
/// chain breaks. Compiled to no-ops in Release.
enum ImageTrace {
    #if DEBUG
    @MainActor private static var sequence = 0
    #endif

    /// A per-build correlation id so interleaved builds (grid/list rows) stay readable.
    @MainActor static func nextBuildId() -> Int {
        #if DEBUG
        sequence += 1
        return sequence
        #else
        return 0
        #endif
    }

    static func log(_ message: @autoclosure () -> String) {
        #if DEBUG
        print("IMGTRACE \(message())")
        #endif
    }

    /// Last `count` characters of an id/path/URL — enough to identify without flooding.
    static func tail(_ value: String?, _ count: Int = 24) -> String {
        guard let value, !value.isEmpty else { return "nil" }
        return value.count <= count ? value : "…" + String(value.suffix(count))
    }
}
