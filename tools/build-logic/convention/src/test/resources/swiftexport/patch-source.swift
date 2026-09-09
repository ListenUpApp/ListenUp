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

extension ExportedKotlinPackages.kotlinx.serialization.encoding.CompositeDecoder {
    @_spi(kotlinx$serialization$ExperimentalSerializationApi)
    public func decodeSequentially() -> Swift.Bool {
        fatalError("'decodeSequentially' is an @_spi requirement that must be implemented by Swift conformers")
    }
    @_spi(kotlinx$serialization$ExperimentalSerializationApi)
    public func decodeSequentially() -> Swift.Bool {
        return kotlinx_serialization_encoding_CompositeDecoder_decodeSequentially_direct(self.__externalRCRef())
    }
}
extension ExportedKotlinPackages.kotlinx.coroutines.flow.MutableSharedFlow {
    @_spi(kotlinx$coroutines$ExperimentalCoroutinesApi)
    public func resetReplayCache() -> Swift.Void {
        fatalError("'resetReplayCache' is an @_spi requirement that must be implemented by Swift conformers")
    }
}

public enum WithDateTimeComponents_SealedType: KotlinRuntimeSupport.SealedType {
    case builder(ExportedKotlinPackages.kotlinx.datetime.format.DateTimeComponentsFormat.Builder_SealedType)
    public var value: Shared._ExportedKotlinPackages_kotlinx_datetime_format_DateTimeFormatBuilder_WithDateTimeComponents {
        get {
            switch self {
            case let .builder(type): type.value
            }
        }
    }
}
public enum DateTimeFormat_SealedType: KotlinRuntimeSupport.SealedType {
    case builder(ExportedKotlinPackages.kotlinx.datetime.format.DateTimeFormat.Builder_SealedType)
    public var value: Swift.Int32 {
        get {
            switch self {
            case let .builder(type): type.value
            }
        }
    }
}
extension ExportedKotlinPackages.kotlinx.datetime.format {
    public final class DateTimeFormat: KotlinRuntime.KotlinBase {
    }
}
