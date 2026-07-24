import KotlinRuntime

@available(*, unavailable)
public static func +(
    this: ExportedKotlinPackages.kotlinx.coroutines.CoroutineDispatcher,
    other: ExportedKotlinPackages.kotlinx.coroutines.CoroutineContext
) -> ExportedKotlinPackages.kotlinx.coroutines.CoroutineContext {
    this._plus(other: other)
}

public static func Format(
    block: (Shared._ExportedKotlinPackages_DateTimeFormatBuilder_WithDate) -> Void
) -> ExportedKotlinPackages.kotlinx.datetime.DateTimeFormat {
    return _Format(block: block)
}

public final class Note: KotlinRuntime.KotlinBase {
    public var description: Swift.String {
        get {
            return self._description_get()
        }
    }
}
