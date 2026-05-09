package com.calypsan.listenup.rpcguard.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType

internal data class RpcServiceModel(
    val declaration: KSClassDeclaration,
    val packageName: String,
    val simpleName: String,
    val methods: List<RpcMethodModel>,
)

internal data class RpcMethodModel(
    val declaration: KSFunctionDeclaration,
    val name: String,
    val parameters: List<RpcParameterModel>,
    val returnShape: ReturnShape,
)

internal data class RpcParameterModel(
    val name: String,
    val type: KSType,
)

internal sealed interface ReturnShape {
    /** `suspend fun X(...): AppResult<R>` — `inner` is `R`. */
    data class SuspendAppResult(
        val inner: KSType,
    ) : ReturnShape

    /** `fun X(...): Flow<RpcEvent<R>>` — `inner` is `R`. */
    data class FlowOfRpcEvent(
        val inner: KSType,
    ) : ReturnShape

    /** Anything else. The processor emits `KSPLogger.error` for these. */
    data class Invalid(
        val reason: String,
    ) : ReturnShape
}
