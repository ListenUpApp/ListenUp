import com.calypsan.listenup.gradle.LISTENUP_FREE_COMPILER_ARGS
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// The JDK every module compiles with; pinned so a newer local or daemon JDK can't shift validation.
val pinnedJdk = 21

kotlin {
    jvmToolchain(pinnedJdk)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(LISTENUP_FREE_COMPILER_ARGS)
    }
}
