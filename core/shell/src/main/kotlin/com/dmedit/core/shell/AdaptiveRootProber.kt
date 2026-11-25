package com.dmedit.core.shell

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.dmedit.core.model.RootEnvironmentInfo
import com.dmedit.core.model.SuVariant
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ShellExecResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val isSuccess: Boolean get() = exitCode == 0
    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")
}

/**
 * Adaptive Root shell manager tailored for both rooted phones (Magisk/KernelSU)
 * and Windows Android Emulators (LDPlayer 9/14, MuMu 12, BlueStacks, Nox, MEmu)
 * whose built-in `su` often rejects Magisk's `--mount-master` flag.
 */
@Singleton
class AdaptiveRootProber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toyboxManager: ToyboxManager
) {
    private val mutex = Mutex()
    @Volatile
    private var activeShell: Shell? = null

    @Volatile
    var cachedInfo: RootEnvironmentInfo = RootEnvironmentInfo()
        private set

    @Volatile
    var rustHelperPath: String? = null
        private set

    suspend fun probe(
        useCustomToybox: Boolean = false,
        customToyboxCommand: String = "",
        forceReprobe: Boolean = false
    ): RootEnvironmentInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!forceReprobe && activeShell?.isAlive == true && cachedInfo.hasRoot) {
                return@withLock cachedInfo
            }

            val emulatorType = EmulatorDetector.detectEmulator()
            val sharedFolder = EmulatorDetector.detectSharedFolder(emulatorType)
            val userId = detectCurrentUserId()

            val (shell, variant, diag) = acquireAdaptiveRootShell()
            activeShell = shell

            if (shell == null || !shell.isRoot) {
                cachedInfo = RootEnvironmentInfo(
                    hasRoot = false,
                    suVariant = SuVariant.UNAVAILABLE,
                    kernelArch = System.getProperty("os.arch") ?: "unknown",
                    emulatorType = emulatorType,
                    androidApi = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE ?: "",
                    activeUserId = userId,
                    sharedFolderPath = sharedFolder,
                    diagnosticMessage = diag
                )
                return@withLock cachedInfo
            }

            val unameRes = runOnShell(shell, "uname -m")
            val kernelArch = unameRes.stdout.firstOrNull()?.trim()?.ifBlank { null }
                ?: (System.getProperty("os.arch") ?: "unknown")

            val toybox = toyboxManager.resolveToybox(
                kernelArch = kernelArch,
                useCustom = useCustomToybox,
                customCommand = customToyboxCommand,
                commandTester = { cmd -> runOnShell(shell, cmd).isSuccess }
            )

            rustHelperPath = runCatching {
                toyboxManager.resolveRustHelper(
                    kernelArch = kernelArch,
                    commandTester = { cmd -> runOnShell(shell, cmd).isSuccess }
                )
            }.getOrNull()

            val helperTag = if (rustHelperPath != null) " | rust-helper" else ""

            cachedInfo = RootEnvironmentInfo(
                hasRoot = true,
                suVariant = variant,
                kernelArch = kernelArch,
                toyboxPath = toybox.commandPrefix,
                toyboxSource = toybox.source,
                emulatorType = emulatorType,
                androidApi = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE ?: "",
                activeUserId = userId,
                sharedFolderPath = sharedFolder,
                diagnosticMessage = "Root sẵn sàng ($variant | kernel=$kernelArch | ${toybox.source}$helperTag)"
            )
            cachedInfo
        }
    }

    suspend fun execRoot(command: String): ShellExecResult = withContext(Dispatchers.IO) {
        val shell = activeShell?.takeIf { it.isAlive } ?: run {
            probe()
            activeShell
        } ?: return@withContext ShellExecResult(
            exitCode = -1,
            stdout = emptyList(),
            stderr = listOf("Root shell chưa được cấp quyền. Hãy bật Root trong cài đặt LDPlayer.")
        )
        runOnShell(shell, command)
    }

    fun buildToolCommand(tool: String, args: String): String {
        val prefix = cachedInfo.toyboxPath.orEmpty().trim()
        return if (prefix.isEmpty()) "$tool $args" else "$prefix $tool $args"
    }

    private fun acquireAdaptiveRootShell(): Triple<Shell?, SuVariant, String> {
        val errors = mutableListOf<String>()

        // 1. Try Magisk / KernelSU mount-master root shell first
        runCatching {
            val s = Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(6)
                .build()
            if (s.isRoot && verifyUidZero(s)) {
                return Triple(s, SuVariant.MAGISK_MOUNT_MASTER, "Magisk/MountMaster")
            }
            s.close()
        }.onFailure { errors += "mount-master: ${it.message}" }

        // 2. Fallback: Standard `su` without --mount-master (LDPlayer / MuMu / Nox / MEmu built-in root)
        runCatching {
            val s = Shell.Builder.create()
                .setTimeout(6)
                .build("su")
            if (s.isRoot && verifyUidZero(s)) {
                return Triple(s, SuVariant.STANDARD_EMULATOR_SU, "Standard su")
            }
            s.close()
        }.onFailure { errors += "su: ${it.message}" }

        // 3. Fallback: Explicit emulator su binary paths
        for (suPath in listOf("/system/xbin/su", "/sbin/su", "/su/bin/su")) {
            runCatching {
                val s = Shell.Builder.create()
                    .setTimeout(5)
                    .build(suPath)
                if (s.isRoot && verifyUidZero(s)) {
                    return Triple(s, SuVariant.CUSTOM_BINARY, "Custom $suPath")
                }
                s.close()
            }.onFailure { errors += "$suPath: ${it.message}" }
        }

        val hint = "Không thể lấy quyền Root. Nếu bạn dùng LDPlayer: mở Cài đặt LDPlayer → Khác → Bật 'Quyền Root' rồi khởi động lại giả lập. (${errors.joinToString("; ")})"
        return Triple(null, SuVariant.UNAVAILABLE, hint)
    }

    private fun verifyUidZero(shell: Shell): Boolean {
        val out = ArrayList<String>()
        shell.newJob().add("id").to(out).exec()
        return out.any { it.contains("uid=0") }
    }

    private fun runOnShell(shell: Shell, command: String): ShellExecResult {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val res = shell.newJob().add(command).to(out, err).exec()
        return ShellExecResult(res.code, out, err)
    }

    private fun detectCurrentUserId(): Int {
        return runCatching {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val serial = um?.getSerialNumberForUser(Process.myUserHandle())?.toInt()
            if (serial != null && serial >= 0) serial else Process.myUid() / 100_000
        }.getOrDefault(0)
    }
}
