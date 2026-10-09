package com.calypsan.listenup.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.serializer
import java.io.File
import java.util.jar.JarFile
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.valueParameters

/**
 * Every argument and result of every `@Rpc` method can cross the wire on every platform.
 *
 * A type needs no annotation of its own inside a `@Serializable` class (the plugin writes its serializer into
 * the field), but a bare RPC argument or result is looked up at call time. On the JVM that lookup quietly builds
 * an enum's serializer by reflection; Kotlin/Native has no reflection and fails. That shipped once:
 * `reviewPersonMatch`'s `role: ContributorRole?` broke every person Review, while tests calling the service
 * directly stayed green. So each type ListenUp declares must carry its plugin-generated `serializer()`, and every
 * type must resolve one.
 */
class RpcSurfaceSerializersTest :
    FunSpec({
        test("every @Rpc method's arguments and result have a serializer that exists on every platform") {
            val services = rpcServices()
            services.map { it.simpleName } shouldContain "MatchingService"

            val unserializable =
                services.flatMap { service ->
                    service.declaredMemberFunctions.flatMap { method ->
                        val types =
                            method.valueParameters.map { it.name.orEmpty() to it.type } + ("result" to method.returnType)
                        types.mapNotNull { (name, type) ->
                            problemWith(type.unwrapFlow())?.let { "${service.simpleName.orEmpty()}.${method.name}($name): $it" }
                        }
                    }
                }

            unserializable.shouldBeEmpty()
        }
    })

/** Why [type] can't cross the wire on every platform, or null when it can. */
private fun problemWith(type: KType): String? {
    val lookup = runCatching { serializer(type) }.exceptionOrNull()
    if (lookup != null) return "$type has no serializer (${lookup.message.orEmpty().lineSequence().first()})"
    return type.classifiers().firstOrNull { !it.isStandardLibrary() && !it.hasGeneratedSerializer() }?.let {
        "${it.qualifiedName.orEmpty()} is not @Serializable — only the JVM can build its serializer, by reflection"
    }
}

/** [this] type's class and, recursively, its type arguments' classes. */
private fun KType.classifiers(): List<KClass<*>> =
    listOfNotNull(classifier as? KClass<*>) + arguments.mapNotNull { it.type }.flatMap { it.classifiers() }

/** kotlinx.serialization ships serializers for the standard library's serializable types on every platform. */
private fun KClass<*>.isStandardLibrary(): Boolean = qualifiedName.orEmpty().startsWith("kotlin.")

/** The plugin's `serializer()`: on the type itself for an object, else on its companion. */
private fun KClass<*>.hasGeneratedSerializer(): Boolean {
    val holder = if (objectInstance != null) java else java.declaredClasses.firstOrNull { it.simpleName == "Companion" }
    return holder?.declaredMethods.orEmpty().any { it.name == "serializer" }
}

/** A streaming method returns `Flow<T>`; each element crosses the wire, so `T` is what needs a serializer. */
private fun KType.unwrapFlow(): KType = if (classifier == Flow::class) arguments.single().type ?: this else this

/** Every `@Rpc` interface compiled into `:contract`, found by walking the classes beside [MatchingService]. */
private fun rpcServices(): List<KClass<*>> {
    val source = MatchingService::class.java.protectionDomain.codeSource
    val root = File(source.location.toURI())
    val classNames =
        if (root.isDirectory) {
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        } else {
            JarFile(root).use { jar ->
                jar
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") }
                    .toList()
            }
        }
    val loader = MatchingService::class.java.classLoader
    return classNames
        .asSequence()
        .filter { it.startsWith("com/calypsan/listenup/api/") && '$' !in it }
        .map { Class.forName(it.removeSuffix(".class").replace('/', '.'), false, loader) }
        .filter { it.isInterface && it.isAnnotationPresent(Rpc::class.java) }
        .map { it.kotlin }
        .toList()
}
