package com.dmedit.feature.backups

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmedit.core.model.BackupArchiveInfo
import com.dmedit.core.model.SaveFileEntry
import com.dmedit.core.nativebridge.RustCoreEngine
import com.dmedit.core.shell.AdaptiveRootProber
import com.dmedit.core.shell.AndroidRootBackend
import com.dmedit.core.shell.AppStoragePaths
import com.dmedit.core.shell.ProfileRepository
import com.dmedit.core.shell.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class BackupsUiState(
    val isLoading: Boolean = true,
    val backupsDirPath: String = "",
    val sharedFolderPath: String? = null,
    val activeProfileId: String = "",
    val activeProfileName: String = "",
    val activePackageName: String = "",
    val backups: List<BackupArchiveInfo> = emptyList(),
    val availableGameFiles: List<SaveFileEntry> = emptyList(),
    val hasSharedPrefs: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class BackupsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rustCore: RustCoreEngine,
    private val rootProber: AdaptiveRootProber,
    private val rootBackend: AndroidRootBackend,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val storagePaths: AppStoragePaths
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupsUiState())
    val uiState: StateFlow<BackupsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val settings = settingsRepository.settings.first()
            val profiles = profileRepository.profiles.value.ifEmpty { profileRepository.loadAllProfiles() }
            val bundle = profiles.firstOrNull { it.profile.id == settings.activeProfileId } ?: profiles.firstOrNull()
            val rootInfo = rootProber.probe()
            val pkg = settings.activePackageOverride?.ifBlank { null }
                ?: bundle?.profile?.packages?.firstOrNull().orEmpty()

            val dir = storagePaths.getBackupsDir()
            val archiveList = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(RustCoreEngine.SKDB_EXTENSION, ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.mapNotNull { file -> runCatching { rustCore.inspectBackup(file) }.getOrNull() }
                    ?: emptyList()
            }

            var gameFiles: List<SaveFileEntry> = emptyList()
            var prefsExists = false
            if (rootInfo.hasRoot && bundle != null && pkg.isNotBlank()) {
                val filesDir = rootBackend.resolveGameFilesDir(rootInfo.activeUserId, pkg, bundle.profile)
                gameFiles = rootBackend.listDirectory(filesDir, bundle.profile)
                    .getOrDefault(emptyList())
                    .filter { !it.stat.isDirectory }
                val prefsPath = rootBackend.resolveSharedPrefsPath(rootInfo.activeUserId, pkg, bundle.profile)
                prefsExists = rootBackend.exists(prefsPath)
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    backupsDirPath = dir.absolutePath,
                    sharedFolderPath = rootInfo.sharedFolderPath,
                    activeProfileId = bundle?.profile?.id.orEmpty(),
                    activeProfileName = bundle?.profile?.displayName.orEmpty(),
                    activePackageName = pkg,
                    backups = archiveList,
                    availableGameFiles = gameFiles,
                    hasSharedPrefs = prefsExists
                )
            }
        }
    }

    fun createBackup(
        customName: String,
        selectedFiles: Set<String>,
        includeSharedPrefs: Boolean,
        sanitizeAccount: Boolean
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            val stageDir = File(context.cacheDir, "backup_stage_${System.nanoTime()}").apply { mkdirs() }
            try {
                val s = _uiState.value
                val bundle = profileRepository.profiles.value.firstOrNull { it.profile.id == s.activeProfileId }
                    ?: error("Chưa chọn Game Profile")
                val rootInfo = rootProber.probe()
                check(rootInfo.hasRoot) { "Cần quyền Root để đọc file save tạo backup" }

                val stagedMap = LinkedHashMap<String, File>()
                for ((idx, entry) in s.availableGameFiles.withIndex()) {
                    if (entry.name in selectedFiles) {
                        val bytes = rootBackend.readBytes(entry.fullPath).getOrThrow()
                        val out = File(stageDir, "f_$idx.bin")
                        withContext(Dispatchers.IO) { out.writeBytes(bytes) }
                        stagedMap[entry.name] = out
                    }
                }

                var prefsName: String? = null
                var stagedPrefsFile: File? = null
                if (includeSharedPrefs && s.hasSharedPrefs) {
                    val prefsPath = rootBackend.resolveSharedPrefsPath(
                        rootInfo.activeUserId,
                        s.activePackageName,
                        bundle.profile
                    )
                    val bytes = rootBackend.readBytes(prefsPath).getOrThrow()
                    prefsName = File(prefsPath).name
                    stagedPrefsFile = File(stageDir, "prefs.xml").also { f ->
                        withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                    }
                }

                check(stagedMap.isNotEmpty() || stagedPrefsFile != null) { "Hãy chọn ít nhất 1 tệp để sao lưu" }

                val ts = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
                val cleanBase = customName.trim().ifBlank { "${bundle.profile.id}_$ts" }
                    .removeSuffix(RustCoreEngine.SKDB_EXTENSION)
                val targetFile = File(storagePaths.getBackupsDir(), "$cleanBase${RustCoreEngine.SKDB_EXTENSION}")

                withContext(Dispatchers.IO) {
                    rustCore.createBackupFromStaging(
                        targetFile = targetFile,
                        profile = bundle.profile,
                        packageName = s.activePackageName,
                        stagedGameFiles = stagedMap,
                        sharedPrefsFileName = prefsName,
                        stagedSharedPrefsFile = stagedPrefsFile,
                        sanitizeAccountData = sanitizeAccount
                    )
                }

                _uiState.update { it.copy(statusMessage = "Đã tạo backup .skdb v2 (Rust Core): ${targetFile.name}") }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Tạo backup thất bại: ${e.message}") }
            } finally {
                stageDir.deleteRecursively()
            }
        }
    }

    fun restoreBackup(archive: BackupArchiveInfo, selectedArchivePaths: Set<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            val stageDir = File(context.cacheDir, "restore_stage_${System.nanoTime()}").apply { mkdirs() }
            try {
                val s = _uiState.value
                val rootInfo = rootProber.probe()
                check(rootInfo.hasRoot) { "Cần quyền Root để khôi phục dữ liệu vào game" }
                check(selectedArchivePaths.isNotEmpty()) { "Hãy chọn ít nhất 1 tệp để khôi phục" }

                val targetProfileId = archive.manifest?.gameProfileId ?: s.activeProfileId
                val bundle = profileRepository.profiles.value.firstOrNull { it.profile.id == targetProfileId }
                    ?: profileRepository.profiles.value.first()
                val targetPkg = archive.manifest?.packageName?.ifBlank { null } ?: s.activePackageName
                check(targetPkg.isNotBlank()) { "Không xác định được gói game đích" }

                val extracted = withContext(Dispatchers.IO) {
                    rustCore.verifyAndExtractToStaging(File(archive.filePath), selectedArchivePaths, stageDir)
                }

                rootBackend.forceStopPackage(targetPkg)
                val filesDir = rootBackend.resolveGameFilesDir(rootInfo.activeUserId, targetPkg, bundle.profile)
                val prefsDir = "${rootBackend.resolveGameDataRoot(rootInfo.activeUserId, targetPkg)}/shared_prefs"

                for (item in extracted) {
                    val destPath = if (item.isSharedPrefs) {
                        "$prefsDir/${item.targetRelativePath}"
                    } else {
                        "$filesDir/${item.targetRelativePath}"
                    }
                    val bytes = withContext(Dispatchers.IO) { File(item.extractedDiskPath).readBytes() }
                    rootBackend.writeBytesTransactional(
                        path = destPath,
                        bytes = bytes,
                        packageNameToStop = null,
                        createRollback = true
                    ).getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Rust Core đã xác thực SHA-256 & khôi phục ${extracted.size} tệp thành công!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Khôi phục thất bại: ${e.message}") }
            } finally {
                stageDir.deleteRecursively()
            }
        }
    }

    fun exportToSharedFolder(archive: BackupArchiveInfo) {
        viewModelScope.launch {
            runCatching {
                val sharedDir = File(_uiState.value.sharedFolderPath ?: "/sdcard/Pictures", "dmedit_backups")
                withContext(Dispatchers.IO) {
                    sharedDir.mkdirs()
                    val dest = File(sharedDir, archive.fileName)
                    File(archive.filePath).copyTo(dest, overwrite = true)
                    dest.absolutePath
                }
            }.onSuccess { destPath ->
                _uiState.update { it.copy(statusMessage = "Đã xuất sang thư mục chia sẻ: $destPath") }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "Xuất thất bại: ${e.message}") }
            }
        }
    }

    fun importBackupUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val name = "imported_${System.currentTimeMillis()}${RustCoreEngine.SKDB_EXTENSION}"
                val dest = File(storagePaths.getBackupsDir(), name)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Không đọc được tệp đã chọn")
                    rustCore.inspectBackup(dest)
                }
                dest.name
            }.onSuccess { importedName ->
                _uiState.update { it.copy(statusMessage = "Đã nhập backup: $importedName") }
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "Tệp backup không hợp lệ: ${e.message}") }
            }
        }
    }

    fun deleteBackup(archive: BackupArchiveInfo) {
        viewModelScope.launch {
            if (File(archive.filePath).delete()) {
                _uiState.update { it.copy(statusMessage = "Đã xóa ${archive.fileName}") }
                refresh()
            } else {
                _uiState.update { it.copy(errorMessage = "Không thể xóa ${archive.fileName}") }
            }
        }
    }
}
