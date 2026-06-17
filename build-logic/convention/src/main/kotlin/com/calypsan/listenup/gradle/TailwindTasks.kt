package com.calypsan.listenup.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
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
import java.security.MessageDigest
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
     * Expected SHA-256 (hex) of the downloaded `tailwindcss-linux-x64` binary.
     *
     * The download pins the version, but not the bytes; without this the task fetches and
     * executes a 43MB binary on trust. Verifying the hash closes that supply-chain gap.
     */
    @get:Input
    abstract val expectedSha256: Property<String>

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

        // Auto-download targets the pinned linux-x64 binary only; bail early on any other platform.
        val osName = System.getProperty("os.name").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty()
        if (!osName.contains("Linux", ignoreCase = true) ||
            osArch !in setOf("x86_64", "amd64")
        ) {
            throw GradleException(
                "Tailwind auto-download only supports linux-x64. Set the TAILWIND_CLI " +
                    "environment variable to a tailwindcss v3 binary for your platform.",
            )
        }

        f.parentFile.mkdirs()
        val url = URI(
            "https://github.com/tailwindlabs/tailwindcss/releases/download/v${version.get()}/tailwindcss-linux-x64",
        ).toURL()
        logger.lifecycle("Downloading Tailwind CLI v${version.get()} ...")
        url.openStream().use { input: InputStream ->
            f.outputStream().use { out: OutputStream -> input.copyTo(out) }
        }

        // Verify the bytes before we ever mark the binary executable.
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        val expected = expectedSha256.get()
        if (!actualSha256.equals(expected, ignoreCase = true)) {
            f.delete()
            throw GradleException(
                "Tailwind CLI checksum mismatch — refusing to execute downloaded binary.\n" +
                    "  expected: $expected\n" +
                    "  actual:   $actualSha256",
            )
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
    /**
     * The Kotlin source tree Tailwind scans for class names. Declared purely for up-to-date
     * tracking: Tailwind reads the content glob from the config relative to the working dir,
     * so this property's path is not passed to the CLI — but its *content* must invalidate the
     * task, otherwise new utility classes in `.kt` files would silently produce stale CSS.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputCss: RegularFileProperty

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
                "-c", configFile.get().asFile.absolutePath,
                "-i", inputCss.get().asFile.absolutePath,
                "-o", outFile.absolutePath,
                // Intentional: this is the production stylesheet served to clients.
                "--minify",
            )
        }
    }
}
