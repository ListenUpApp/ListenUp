package com.calypsan.listenup.server

import io.github.classgraph.ClassGraph
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.serialization.serializer

/**
 * Native-image metadata capture, not a behavioural test.
 *
 * kotlinx.serialization resolves a `@Serializable` type's serializer through a generated
 * `Companion.serializer()`. kotlinx.rpc resolves serializers for generic return types
 * (e.g. `AppResult<T>`) reflectively at runtime, so those companions must be registered for
 * reflection in the native image. The tracing agent only captures the companions that other
 * tests actually serialize, leaving untested sealed/serializable subtypes (e.g.
 * `AuthError.AccountDenied`, `AppResult.Failure`) absent — which then fail at image
 * heap-writing time.
 *
 * This walks the reflective `serializer()` path for *every* contract `@Serializable` type so
 * the agent captures the complete companion set in a single pass. "Metadata coverage == test
 * coverage": this is the test that makes the coverage total.
 */
class ContractSerializerRegistrationTest :
    FunSpec({
        test("resolve serializer() for every contract @Serializable type (agent capture)") {
            // A concrete KSerializer to satisfy the type-parameter slots of generic
            // `serializer(KSerializer<T0>, …)` overloads; erasure makes the element type irrelevant.
            val stub = serializer<String>()

            val serializableTypes =
                ClassGraph()
                    .enableAnnotationInfo()
                    .acceptPackages("com.calypsan.listenup.api")
                    .scan()
                    .use { result ->
                        result.getClassesWithAnnotation("kotlinx.serialization.Serializable").names
                    }

            serializableTypes.size shouldBeGreaterThan 0

            serializableTypes.forEach { typeName ->
                runCatching {
                    val type = Class.forName(typeName)
                    // @Serializable object / enum: serializer() lives on the type itself or a
                    // synthetic Companion; data/sealed types expose a public static `Companion`.
                    val companion =
                        runCatching { type.getField("Companion").get(null) }.getOrNull() ?: type
                    companion.javaClass.methods
                        .filter { it.name == "serializer" }
                        .forEach { method ->
                            runCatching {
                                val args = Array(method.parameterCount) { stub }
                                method.invoke(companion, *args)
                            }
                        }
                }
            }
        }
    })
