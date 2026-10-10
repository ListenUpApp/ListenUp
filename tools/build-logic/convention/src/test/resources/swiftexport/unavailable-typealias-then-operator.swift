import KotlinRuntime

@available(*, unavailable)
public typealias Old = Swift.Int

public static func +(
    this: ExportedKotlinPackages.kotlin.time.Duration,
    other: ExportedKotlinPackages.kotlin.time.Duration
) -> ExportedKotlinPackages.kotlin.time.Duration {
    this._plus(other: other)
}
