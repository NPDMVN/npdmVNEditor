package com.dmedit.core.shell

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ToyboxResolution(
    val commandPrefix: String?,
    val source: String
)

@Singleton
class ToyboxManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Resolves the best working toybox/busybox command for the ACTUAL kernel architecture
     * reported by `uname -m` inside the root shell (critical for x86_64 emulators like LDPlayer).
     */
    fun resolveToybox(
        kernelArch: String,
        useCustom: Boolean,
        customCommand: String,
        commandTester: (String) -> Boolean
    ): ToyboxResolution {
        if (useCustom && customCommand.isNotBlank()) {
            val trimmed = customCommand.trim()
            if (commandTester("$trimmed true")) {
                return ToyboxResolution(trimmed, "custom")
            }
        }

        // 1. Try bundled static toybox matching real kernel architecture (uname -m)
        val assetName = mapKernelArchToAsset(kernelArch)
        if (assetName != null) {
            val extracted = extractBundledAssetIfPresent(assetName)
            if (extracted != null && commandTester("${ShellEscaper.quote(extracted.absolutePath)} true")) {
                return ToyboxResolution(ShellEscaper.quote(extracted.absolutePath), "bundled:$assetName")
            }
        }

        // 2. Try standard system toybox/busybox (LDPlayer / AOSP almost always ships /system/bin/toybox)
        val systemCandidates = listOf(
            "/system/bin/toybox" to "system:/system/bin/toybox",
            "toybox" to "system:toybox",
            "/system/xbin/busybox" to "system:/system/xbin/busybox",
            "busybox" to "system:busybox"
        )
        for ((cmd, label) in systemCandidates) {
            if (commandTester("$cmd true")) {
                return ToyboxResolution(cmd, label)
            }
        }

        // 3. Bare shell builtins fallback (stat/ls/cp/mv/chmod/chown exist directly in /system/bin on Android 9-15)
        return ToyboxResolution("", "system:direct-bin")
    }

    /**
     * Extracts and verifies our standalone Rust root helper (`dmedit-helper-<arch>`)
     * for reliable POSIX atomic writes without shell quoting or toybox flag differences.
     */
    fun resolveRustHelper(
        kernelArch: String,
        commandTester: (String) -> Boolean
    ): String? {
        val assetName = when (kernelArch.lowercase().trim()) {
            "x86_64", "amd64" -> "dmedit-helper-x86_64"
            "i386", "i686", "x86" -> "dmedit-helper-x86"
            "aarch64", "arm64", "armv8l" -> "dmedit-helper-aarch64"
            "armv7l", "armv7a", "arm" -> "dmedit-helper-armv7m"
            else -> null
        } ?: return null

        val extracted = extractBundledAssetIfPresent(assetName) ?: return null
        val quoted = ShellEscaper.quote(extracted.absolutePath)
        return if (commandTester("$quoted ping")) quoted else null
    }

    private fun mapKernelArchToAsset(kernelArch: String): String? = when (kernelArch.lowercase().trim()) {
        "x86_64", "amd64" -> "toybox-x86_64"
        "i386", "i686", "x86" -> "toybox-x86"
        "aarch64", "arm64", "armv8l" -> "toybox-aarch64"
        "armv7l", "armv7a", "arm" -> "toybox-armv7m"
        else -> null
    }

    private fun extractBundledAssetIfPresent(assetFileName: String): File? {
        val assetPath = "bin/$assetFileName"
        val available = runCatching { context.assets.list("bin")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        if (assetFileName !in available) return null

        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val target = File(binDir, assetFileName)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.setExecutable(true, false)
        return target
    }
}

object ShellEscaper {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
