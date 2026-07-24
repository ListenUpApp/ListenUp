import KotlinRuntime

public final class _ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Failure: KotlinRuntime.KotlinBase {
    public var error: any ExportedKotlinPackages.com.calypsan.listenup.api.error.AppError {
        get {
            return KotlinRuntime.KotlinBase.__createProtocolWrapper(externalRCRef: com_calypsan_listenup_api_result_AppResult_Failure_error_get(self.__externalRCRef())) as! any ExportedKotlinPackages.com.calypsan.listenup.api.error.AppError
        }
    }
}
public final class _ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Success: KotlinRuntime.KotlinBase {
    public var data: (any KotlinRuntimeSupport._KotlinBridgeable)? {
        get {
            return { switch com_calypsan_listenup_api_result_AppResult_Success_data_get(self.__externalRCRef()) { case nil: .none; case let res: KotlinRuntime.KotlinBase.__createBridgeable(externalRCRef: res); } }()
        }
    }
}
