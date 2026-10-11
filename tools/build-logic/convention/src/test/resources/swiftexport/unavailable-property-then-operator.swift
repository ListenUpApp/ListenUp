import KotlinRuntime

@available(*, unavailable, message: "Deprecated in Kotlin")
public var legacyFlag: Swift.Bool {
    get {
        return Shared_legacyFlag_get()
    }
}

public static func +(
    this: ExportedKotlinPackages.kotlin.time.Duration,
    other: ExportedKotlinPackages.kotlin.time.Duration
) -> ExportedKotlinPackages.kotlin.time.Duration {
    this._plus(other: other)
}
