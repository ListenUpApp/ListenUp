import KotlinRuntime

public protocol Source {
    func requirement() -> Swift.Int
}

public func plain() -> Swift.Int {
    return 1
}

public protocol Sink {
    func other() -> Swift.Int
}

public static func Format(
    block: (Shared._ExportedKotlinPackages_DateTimeFormatBuilder_WithDate) -> Void
) -> ExportedKotlinPackages.kotlinx.datetime.DateTimeFormat {
    return _Format(block: block)
}
