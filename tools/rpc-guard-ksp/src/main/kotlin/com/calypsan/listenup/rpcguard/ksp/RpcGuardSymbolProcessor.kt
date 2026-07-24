package com.calypsan.listenup.rpcguard.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

private const val RPC_ANNOTATION_FQN = "kotlinx.rpc.annotations.Rpc"
private const val APP_RESULT_FQN = "com.calypsan.listenup.api.result.AppResult"
private const val FLOW_FQN = "kotlinx.coroutines.flow.Flow"
private const val RPC_EVENT_FQN = "com.calypsan.listenup.api.streaming.RpcEvent"

internal class RpcGuardSymbolProcessor(
    private val env: SymbolProcessorEnvironment,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        // TODO(Task 5): @Rpc applied to a non-interface (class/object) is currently
        // silently filtered. Add an explicit error branch + a regression test:
        //   "@Rpc may only be applied to interfaces; <Name> is a <classKind>".
        val services =
            resolver
                .getSymbolsWithAnnotation(RPC_ANNOTATION_FQN)
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.INTERFACE }
                .toList()

        val models = services.mapNotNull { decl -> buildServiceModel(decl) }

        // warn() is used instead of info() because kctfork 0.12.1's TestKSPLogger only
        // forwards ERROR/EXCEPTION to the MessageCollector during KSP execution; info
        // messages never reach result.messages. warn() is visible in test assertions.
        for (model in models) {
            val methodNames = model.methods.joinToString(", ") { it.name }
            env.logger.warn("[rpc-guard] discovered: ${model.packageName}.${model.simpleName} [$methodNames]")
            com.calypsan.listenup.rpcguard.ksp.codegen.GuardedClassWriter
                .write(model, env.codeGenerator)
        }
        com.calypsan.listenup.rpcguard.ksp.codegen.DispatcherWriter
            .write(models, env.codeGenerator)

        return emptyList()
    }

    private fun buildServiceModel(decl: KSClassDeclaration): RpcServiceModel? {
        val methods = decl.getDeclaredFunctions().toList()
        val analyzed =
            methods.map { fn ->
                RpcMethodModel(
                    declaration = fn,
                    name = fn.simpleName.asString(),
                    parameters =
                        fn.parameters.map { p ->
                            RpcParameterModel(p.name?.asString() ?: "_", p.type.resolve())
                        },
                    returnShape = analyzeReturnShape(fn),
                )
            }

        var hasInvalid = false
        for (method in analyzed) {
            val shape = method.returnShape
            if (shape is ReturnShape.Invalid) {
                env.logger.error(
                    "${decl.simpleName.asString()}.${method.name}: ${shape.reason}",
                    method.declaration,
                )
                hasInvalid = true
            }
        }
        if (hasInvalid) return null

        return RpcServiceModel(
            declaration = decl,
            packageName = decl.packageName.asString(),
            simpleName = decl.simpleName.asString(),
            methods = analyzed,
        )
    }

    private fun analyzeReturnShape(fn: KSFunctionDeclaration): ReturnShape {
        val isSuspend = Modifier.SUSPEND in fn.modifiers
        val returnType =
            fn.returnType?.resolve()
                ?: return ReturnShape.Invalid("missing return type")

        val returnFqn = returnType.declaration.qualifiedName?.asString()

        return if (isSuspend) {
            if (returnFqn != APP_RESULT_FQN) {
                ReturnShape.Invalid("suspend functions on @Rpc interfaces must return AppResult<*>")
            } else {
                val inner =
                    returnType.arguments
                        .firstOrNull()
                        ?.type
                        ?.resolve()
                        ?: return ReturnShape.Invalid("AppResult<?> missing type argument")
                ReturnShape.SuspendAppResult(inner)
            }
        } else {
            if (returnFqn != FLOW_FQN) {
                return ReturnShape.Invalid("non-suspend functions on @Rpc interfaces must return Flow<RpcEvent<*>>")
            }
            val flowArg =
                returnType.arguments
                    .firstOrNull()
                    ?.type
                    ?.resolve()
                    ?: return ReturnShape.Invalid("Flow<?> missing type argument")
            val flowArgFqn = flowArg.declaration.qualifiedName?.asString()
            if (flowArgFqn != RPC_EVENT_FQN) {
                return ReturnShape.Invalid("non-suspend functions on @Rpc interfaces must return Flow<RpcEvent<*>>")
            }
            val inner =
                flowArg.arguments
                    .firstOrNull()
                    ?.type
                    ?.resolve()
                    ?: return ReturnShape.Invalid("RpcEvent<?> missing type argument")
            ReturnShape.FlowOfRpcEvent(inner)
        }
    }
}

private fun KSClassDeclaration.getDeclaredFunctions(): Sequence<KSFunctionDeclaration> =
    declarations.filterIsInstance<KSFunctionDeclaration>()
