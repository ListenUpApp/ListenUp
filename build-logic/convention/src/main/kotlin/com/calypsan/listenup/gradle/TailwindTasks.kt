package com.calypsan.listenup.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import javax.inject.Inject

/**
 * Downloads the Tailwind v3 standalone CLI binary from GitHub releases.
 *
 * Configuration-cache compatible: all state is captured via Gradle property APIs.
 * Uses [ExecOperations] injection (not `project.exec`) so no project reference is held.
 */
abstract class TailwindResolveCliTask : DefaultTask() {
    @get:OutputFile
    abstract val outputBin: RegularFileProperty

    @get:Input
    abstract val version: Property<String>

    /**
     * When set to a non-empty value this task is a no-op (env var already provides the binary).
     * Declared as [Input] so the configuration cache can serialize it without capturing a closure.
     */
    @get:Input
    @get:Optional
    abstract val tailwindCliEnv: Property<String>

    @TaskAction
    fun resolve() {
        // Skip if the caller already has a CLI on PATH/env, or if the binary is cached.
        if (tailwindCliEnv.orNull?.isNotEmpty() == true) return
        val f = outputBin.get().asFile
        if (f.exists()) return
        f.parentFile.mkdirs()
        val url = URI(
            "https://github.com/tailwindlabs/tailwindcss/releases/download/v${version.get()}/tailwindcss-linux-x64",
        ).toURL()
        logger.lifecycle("Downloading Tailwind CLI v${version.get()} ...")
        url.openStream().use { input: InputStream ->
            f.outputStream().use { out: OutputStream -> input.copyTo(out) }
        }
        f.setExecutable(true)
    }
}

/**
 * Runs the Tailwind CLI to generate the stylesheet from scanned Kotlin class names.
 *
 * Configuration-cache compatible: CLI path resolved at execution time from the env var
 * or the [downloadedBin] output, never from the script's [Project].
 */
abstract class TailwindGenerateTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val configFilePath: Property<String>

    @get:Input
    abstract val inputCssPath: Property<String>

    @get:OutputFile
    abstract val outputCss: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val downloadedBin: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val tailwindCliEnv: Property<String>

    @TaskAction
    fun generate() {
        val outFile = outputCss.get().asFile
        outFile.parentFile.mkdirs()
        val cli = tailwindCliEnv.orNull
            ?: downloadedBin.get().asFile.takeIf { it.exists() }?.absolutePath
            ?: "tailwindcss"
        execOps.exec {
            commandLine(
                cli,
                "-c", configFilePath.get(),
                "-i", inputCssPath.get(),
                "-o", outFile.absolutePath,
                "--minify",
            )
        }
    }
}
