import com.calypsan.listenup.gradle.LISTENUP_FREE_COMPILER_ARGS
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(LISTENUP_FREE_COMPILER_ARGS)
    }
}
