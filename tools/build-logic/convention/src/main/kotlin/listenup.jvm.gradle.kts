import com.calypsan.listenup.gradle.LISTENUP_FREE_COMPILER_ARGS
import com.calypsan.listenup.gradle.useStrictKotestEquality
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("listenup.detekt")
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

// Kotest's shouldBe is plain equals in every JVM test task (see KotestEquality.kt).
tasks.withType<Test>().configureEach { useStrictKotestEquality() }
