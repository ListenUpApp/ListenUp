package com.calypsan.listenup.rpcguard.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** ServiceLoader entry point for the RPC-guard KSP processor. See [RpcGuardSymbolProcessor]. */
class RpcGuardSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = RpcGuardSymbolProcessor(environment)
}
