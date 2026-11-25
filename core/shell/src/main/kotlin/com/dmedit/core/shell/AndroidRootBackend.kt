package com.dmedit.core.shell

import android.content.Context
import com.dmedit.core.model.FileStat
import com.dmedit.core.model.GameProfile
import com.dmedit.core.model.SaveFileEntry
import com.dmedit.core.nativebridge.RustCoreEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface FileSystemBackend {
    suspend fun listDirectory(dirPath: String, profile: GameProfile): Result<List<SaveFileEntry>>
    suspend fun statFile(path: String): FileStat?
    suspend fun readBytes(path: String): Result<ByteArray>
    suspend fun writeBytesTransactional(
        path: String,
        bytes: ByteArray,
        packageNameToStop: String? = null,
        createRollback: Boolean = true
    ): Result<File?>
    suspend fun deleteFile(path: String): Result<Unit>
    suspend fun renameFile(oldPath: String, newPath: String): Result<Unit>
    suspend fun exists(path: String): Boolean
    suspend fun forceStopPackage(packageName: String): Result<Unit>
    suspend fun softRebootSystemServer(): Result<Unit>
    suspend fun fullRebootDevice(): Result<Unit>
    suspend fun listInstalledPackages(): List<String>
}

@Singleton
class AndroidRootBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootProber: AdaptiveRootProber,
    private val rustCore: RustCoreEngine
) : FileSystemBackend {

    private val stagingDir: File
        get() = File(context.cacheDir, "staging").apply { mkdirs() }

    private val rollbackDir: File
        get() = File(context.cacheDir, "rollback").apply { mkdirs() }

    fun resolveGameDataRoot(userId: Int, packageName: String): String =
        "/data/user/$userId/$packageName"

    fun resolveGameFilesDir(userId: Int, packageName: String, profile: GameProfile): String =
        "${resolveGameDataRoot(userId, packageName)}/${profile.paths.filesDir.trim('/')}"

    fun resolveSharedPrefsPath(userId: Int, packageName: String, profile: GameProfile): String =
        "${resolveGameDataRoot(userId, packageName)}/${profile.paths.resolveSharedPrefs(packageName).trimStart('/')}"

    fun resolveSsaidPath(userId: Int): String = "/data/system/users/$userId/settings_ssaid.xml"

    override suspend fun listDirectory(dirPath: String, profile: GameProfile): Result<List<SaveFileEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanDir = dirPath.trimEnd('/')
                val lsCmd = rootProber.buildToolCommand("ls", "-1 -p ${ShellEscaper.quote(cleanDir)}")
                val lsRes = rootProber.execRoot(lsCmd)
                if (!lsRes.isSuccess) {
                    error("Không đọc được thư mục '$cleanDir': ${lsRes.stderrText.ifBlank { "Exit ${lsRes.exitCode}" }}")
                }

                val entries = mutableListOf<SaveFileEntry>()

                for (rawLine in lsRes.stdout) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty()) continue
                    val isDir = trimmed.endsWith("/")
                    val name = trimmed.removeSuffix("/")
                    val fullPath = "$cleanDir/$name"
                    val stat = statFile(fullPath) ?: FileStat(isDirectory = isDir)
                    val matchedRule = if (!stat.isDirectory) {
                        runCatching { rustCore.matchFileRule(profile, name) }.getOrNull()
                    } else null

                    entries += SaveFileEntry(
                        name = name,
                        fullPath = fullPath,
                        relativePath = name,
                        stat = stat.copy(isDirectory = isDir || stat.isDirectory),
                        matchedRule = matchedRule,
                        isGameRelevant = matchedRule != null ||
                            name.endsWith(".data", ignoreCase = true) ||
                            name.endsWith(".xml", ignoreCase = true) ||
                            name.endsWith(".json", ignoreCase = true) ||
                            name.endsWith(".rij", ignoreCase = true) ||
                            name.endsWith(".new", ignoreCase = true)
                    )
                }

                entries.sortedWith(
                    compareBy<SaveFileEntry> { entry ->
                        val lower = entry.name.lowercase()
                        val hasSchema = !entry.matchedRule?.visualSchema.isNullOrBlank()
                        val isModern = lower.endsWith(".data.new") || lower.endsWith(".data.rij")
                        when {
                            !entry.stat.isDirectory && lower == "game.data" -> 0
                            !entry.stat.isDirectory && hasSchema && isModern -> 1
                            !entry.stat.isDirectory && isModern -> 2
                            !entry.stat.isDirectory && hasSchema -> 3
                            !entry.stat.isDirectory && entry.matchedRule != null -> 4
                            entry.stat.isDirectory -> 5
                            else -> 6
                        }
                    }.thenBy { it.name.lowercase() }
                )
            }
        }

    override suspend fun statFile(path: String): FileStat? = withContext(Dispatchers.IO) {
        val qPath = ShellEscaper.quote(path)
        var parsedStat: FileStat? = null

        // 1. Prefer native Rust root helper (`dmedit-helper stat`) for exact JSON stat output
        val helper = rootProber.rustHelperPath
        if (!helper.isNullOrBlank()) {
            val hRes = rootProber.execRoot("$helper stat $qPath")
            if (hRes.isSuccess && hRes.stdout.isNotEmpty()) {
                parsedStat = runCatching {
                    val obj = Json.parseToJsonElement(hRes.stdout.first().trim()) as? JsonObject ?: return@runCatching null
                    FileStat(
                        modeOctal = (obj["modeOctal"] as? JsonPrimitive)?.content ?: "660",
                        uid = (obj["uid"] as? JsonPrimitive)?.intOrNull ?: 0,
                        gid = (obj["gid"] as? JsonPrimitive)?.intOrNull ?: 0,
                        sizeBytes = (obj["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
                        isDirectory = (obj["isDirectory"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        isSymlink = (obj["isSymlink"] as? JsonPrimitive)?.booleanOrNull ?: false
                    )
                }.getOrNull()
            }
        }

        // 2. Fallback to Toybox/BusyBox `stat`
        if (parsedStat == null) {
            val statCmd = rootProber.buildToolCommand("stat", "-c '%a %u %g %s %F' $qPath")
            val res = rootProber.execRoot(statCmd)
            if (res.isSuccess && res.stdout.isNotEmpty()) {
                val parts = res.stdout.first().trim().split(Regex("\\s+"), limit = 5)
                if (parts.size >= 4) {
                    val fileType = parts.getOrNull(4)?.lowercase().orEmpty()
                    parsedStat = FileStat(
                        modeOctal = parts[0],
                        uid = parts[1].toIntOrNull() ?: 0,
                        gid = parts[2].toIntOrNull() ?: 0,
                        sizeBytes = parts[3].toLongOrNull() ?: 0L,
                        isDirectory = fileType.contains("directory"),
                        isSymlink = fileType.contains("symbolic link")
                    )
                }
            }
        }

        val base = parsedStat ?: return@withContext null

        val selinuxRes = rootProber.execRoot("ls -Z $qPath 2>/dev/null")
        val selinuxCtx = selinuxRes.stdout.firstOrNull()
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull { it.contains(":") && it.startsWith("u:") }

        base.copy(selinuxContext = selinuxCtx)
    }

    override suspend fun readBytes(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val tempOut = File(stagingDir, "read_${System.nanoTime()}.bin")
            try {
                val qSrc = ShellEscaper.quote(path)
                val qDst = ShellEscaper.quote(tempOut.absolutePath)
                val cpCmd = rootProber.buildToolCommand("cp", "-f $qSrc $qDst")
                val chmodCmd = rootProber.buildToolCommand("chmod", "644 $qDst")
                val res = rootProber.execRoot("$cpCmd && $chmodCmd")
                check(res.isSuccess && tempOut.exists()) {
                    "Không thể đọc tệp '$path': ${res.stderrText}"
                }
                tempOut.readBytes()
            } finally {
                tempOut.delete()
            }
        }
    }

    override suspend fun writeBytesTransactional(
        path: String,
        bytes: ByteArray,
        packageNameToStop: String?,
        createRollback: Boolean
    ): Result<File?> = withContext(Dispatchers.IO) {
        runCatching {
            val inferredPkg = extractPackageFromAppDataPath(path)
            if (!packageNameToStop.isNullOrBlank()) {
                forceStopPackage(packageNameToStop)
                if (!inferredPkg.isNullOrBlank() && inferredPkg != packageNameToStop) {
                    forceStopPackage(inferredPkg)
                }
            }

            val existingStat = statFile(path)
            var rollbackFile: File? = null

            if (createRollback && existingStat != null && !existingStat.isDirectory) {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safeName = File(path).name
                val snapshot = File(rollbackDir, "${ts}_$safeName")
                readBytes(path).onSuccess { orig ->
                    snapshot.writeBytes(orig)
                    rollbackFile = snapshot
                }
            }

            stagingDir.setReadable(true, false)
            stagingDir.setExecutable(true, false)
            val stageFile = File(stagingDir, "write_${System.nanoTime()}.bin")
            try {
                stageFile.writeBytes(bytes)
                stageFile.setReadable(true, false)
                val parentDir = File(path).parent ?: "/"
                val grandParentDir = File(parentDir).parent
                val tmpTarget = "$path.dmedit.tmp"

                val qStage = ShellEscaper.quote(stageFile.absolutePath)
                val qTmpTarget = ShellEscaper.quote(tmpTarget)
                val qTarget = ShellEscaper.quote(path)
                val qParent = ShellEscaper.quote(parentDir)

                // Derive reliable owner UID/GID — auto-heal if file was previously owned by root (uid=0) inside /data/user|data/
                val parentStat = statFile(parentDir)
                val grandParentStat = grandParentDir?.let { statFile(it) }
                val isAppDataPath = path.startsWith("/data/user/") || path.startsWith("/data/data/")
                val targetUid = when {
                    existingStat != null && existingStat.uid > 0 -> existingStat.uid
                    parentStat != null && parentStat.uid > 0 -> parentStat.uid
                    isAppDataPath && grandParentStat != null && grandParentStat.uid > 0 -> grandParentStat.uid
                    else -> existingStat?.uid ?: 0
                }
                val targetGid = when {
                    existingStat != null && existingStat.gid > 0 -> existingStat.gid
                    parentStat != null && parentStat.gid > 0 -> parentStat.gid
                    isAppDataPath && grandParentStat != null && grandParentStat.gid > 0 -> grandParentStat.gid
                    else -> targetUid
                }
                val targetSelinux = existingStat?.selinuxContext ?: parentStat?.selinuxContext
                val modeToApply = if (existingStat != null && existingStat.modeOctal.matches(Regex("^[0-7]{3,4}$"))) {
                    existingStat.modeOctal
                } else {
                    "660"
                }

                // Step 1: Try native Rust `dmedit-helper atomic-write` first (zero Toybox quoting/flag quirks)
                var writeSucceeded = false
                var helperError = ""
                val helper = rootProber.rustHelperPath
                if (!helper.isNullOrBlank()) {
                    val hCmd = "$helper atomic-write $qStage $qTarget $modeToApply $targetUid $targetGid"
                    val hRes = rootProber.execRoot(hCmd)
                    if (hRes.isSuccess) {
                        writeSucceeded = true
                    } else {
                        helperError = hRes.stderrText.ifBlank { "exit=${hRes.exitCode}" }
                    }
                }

                // Step 2: POSIX shell fallback (mkdir -> cp && mv -> cat > && mv) — NEVER mask with trailing `|| true`!
                if (!writeSucceeded) {
                    val mkdirRes = rootProber.execRoot(rootProber.buildToolCommand("mkdir", "-p $qParent"))
                    check(mkdirRes.isSuccess) {
                        "Không tạo được thư mục '$parentDir': ${mkdirRes.stderrText.ifBlank { mkdirRes.stdoutText }}"
                    }

                    val writeCmd = "${rootProber.buildToolCommand("cp", "-f $qStage $qTmpTarget")} && " +
                        rootProber.buildToolCommand("mv", "-f $qTmpTarget $qTarget")
                    val writeRes = rootProber.execRoot(writeCmd)
                    if (!writeRes.isSuccess) {
                        val catRes = rootProber.execRoot("cat $qStage > $qTmpTarget && mv -f $qTmpTarget $qTarget")
                        check(catRes.isSuccess) {
                            "Ghi tệp '$path' thất bại: helper=[$helperError] cp=[${writeRes.stderrText}] cat=[${catRes.stderrText}]"
                        }
                    }

                    // Restore exact Linux mode, owner UID, and group GID separately for maximum Toybox compatibility
                    rootProber.execRoot(rootProber.buildToolCommand("chmod", "$modeToApply $qTarget"))
                    if (targetUid > 0) {
                        val chownBoth = rootProber.execRoot(
                            rootProber.buildToolCommand("chown", "$targetUid:$targetGid $qTarget")
                        )
                        if (!chownBoth.isSuccess) {
                            rootProber.execRoot(rootProber.buildToolCommand("chown", "$targetUid $qTarget"))
                            if (targetGid > 0) {
                                rootProber.execRoot(rootProber.buildToolCommand("chgrp", "$targetGid $qTarget"))
                            }
                        }
                    }
                }

                // Step 3: Restore SELinux context in an ISOLATED command so permissive/disabled SELinux never masks errors
                if (!targetSelinux.isNullOrBlank()) {
                    rootProber.execRoot("chcon ${ShellEscaper.quote(targetSelinux)} $qTarget 2>/dev/null")
                } else {
                    rootProber.execRoot("restorecon $qTarget 2>/dev/null")
                }

                // Step 4: Flush filesystem buffers on emulators (LDPlayer)
                rootProber.execRoot("sync 2>/dev/null")

                // Step 5: Mandatory post-write disk read-back verification!
                val verifyBytes = readBytes(path).getOrElse { err ->
                    error("Đã ghi nhưng không đọc kiểm chứng lại được '$path': ${err.message}")
                }
                check(verifyBytes.contentEquals(bytes)) {
                    "Xác thực ghi đĩa thất bại cho '$path' (mong đợi ${bytes.size} bytes, thực tế trên đĩa ${verifyBytes.size} bytes). Hãy kiểm tra quyền Root/SELinux."
                }

                rollbackFile
            } finally {
                stageFile.delete()
            }
        }
    }

    private fun extractPackageFromAppDataPath(path: String): String? {
        val m = Regex("^/data/(?:user/\\d+|data)/([^/]+)/").find(path)
        return m?.groupValues?.getOrNull(1)
    }

    override suspend fun deleteFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = rootProber.buildToolCommand("rm", "-rf ${ShellEscaper.quote(path)}")
            val res = rootProber.execRoot(cmd)
            check(res.isSuccess) { "Xóa thất bại: ${res.stderrText}" }
        }
    }

    override suspend fun renameFile(oldPath: String, newPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = rootProber.buildToolCommand(
                "mv",
                "-f ${ShellEscaper.quote(oldPath)} ${ShellEscaper.quote(newPath)}"
            )
            val res = rootProber.execRoot(cmd)
            check(res.isSuccess) { "Đổi tên thất bại: ${res.stderrText}" }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        rootProber.execRoot("[ -e ${ShellEscaper.quote(path)} ]").isSuccess
    }

    override suspend fun forceStopPackage(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val qPkg = ShellEscaper.quote(packageName)
            val res = rootProber.execRoot("am force-stop $qPkg 2>/dev/null || killall $qPkg 2>/dev/null || true")
            check(res.isSuccess) { "Không dừng được tiến trình $packageName" }
        }
    }

    override suspend fun softRebootSystemServer(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val res = rootProber.execRoot("killall system_server || setprop ctl.restart zygote")
            check(res.isSuccess) { "Soft reboot thất bại: ${res.stderrText}" }
        }
    }

    override suspend fun fullRebootDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val res = rootProber.execRoot("reboot")
            check(res.isSuccess) { "Reboot thất bại: ${res.stderrText}" }
        }
    }

    override suspend fun listInstalledPackages(): List<String> = withContext(Dispatchers.IO) {
        val pmRes = rootProber.execRoot("pm list packages -3 2>/dev/null")
        if (pmRes.isSuccess && pmRes.stdout.isNotEmpty()) {
            pmRes.stdout.mapNotNull { line ->
                line.trim().takeIf { it.startsWith("package:") }?.removePrefix("package:")
            }.sorted()
        } else {
            runCatching {
                context.packageManager.getInstalledApplications(0).map { it.packageName }.sorted()
            }.getOrDefault(emptyList())
        }
    }
}
