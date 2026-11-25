package com.dmedit.feature.spoofer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmedit.core.model.EmulatorType
import com.dmedit.core.model.SpooferState
import com.dmedit.core.nativebridge.RustCoreEngine
import com.dmedit.core.shell.AdaptiveRootProber
import com.dmedit.core.shell.AndroidRootBackend
import com.dmedit.core.shell.AppStoragePaths
import com.dmedit.core.shell.ProfileRepository
import com.dmedit.core.shell.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SpooferViewModel @Inject constructor(
    private val rustCore: RustCoreEngine,
    private val rootProber: AdaptiveRootProber,
    private val rootBackend: AndroidRootBackend,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val storagePaths: AppStoragePaths
) : ViewModel() {

    private val _state = MutableStateFlow(SpooferState(isLoading = true))
    val state: StateFlow<SpooferState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val settings = settingsRepository.settings.first()
                val profiles = profileRepository.profiles.value.ifEmpty { profileRepository.loadAllProfiles() }
                val bundle = profiles.firstOrNull { it.profile.id == settings.activeProfileId } ?: profiles.first()
                val rootInfo = rootProber.probe()
                val pkg = settings.activePackageOverride?.ifBlank { null }
                    ?: bundle.profile.packages.firstOrNull().orEmpty()

                check(rootInfo.hasRoot) { rootInfo.diagnosticMessage }

                val prefsPath = rootBackend.resolveSharedPrefsPath(rootInfo.activeUserId, pkg, bundle.profile)
                val foundAccountKeys = if (rootBackend.exists(prefsPath)) {
                    val xml = rootBackend.readBytes(prefsPath).getOrThrow().toString(Charsets.UTF_8)
                    withContext(Dispatchers.Default) {
                        rustCore.findAccountKeys(xml, bundle.profile.sanitizer)
                    }
                } else {
                    emptyList()
                }

                val ssaidPath = rootBackend.resolveSsaidPath(rootInfo.activeUserId)
                val ssaidBytes = rootBackend.readBytes(ssaidPath).getOrThrow()
                val inspection = withContext(Dispatchers.Default) {
                    rustCore.inspectSsaid(ssaidBytes, pkg)
                }
                val origBackupFile = storagePaths.getSsaidBackupFile(rootInfo.activeUserId)

                SpooferState(
                    packageName = pkg,
                    accountKeysFound = foundAccountKeys,
                    currentSsaid = inspection.entry?.value,
                    ssaidFormat = inspection.format,
                    ssaidFilePath = ssaidPath,
                    hasOriginalSsaidBackup = origBackupFile.exists(),
                    isEmulator = rootInfo.emulatorType != EmulatorType.PHYSICAL_DEVICE,
                    isLoading = false
                )
            }.onSuccess { loaded ->
                _state.value = loaded
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun clearAccountData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            runCatching {
                val settings = settingsRepository.settings.first()
                val profiles = profileRepository.profiles.value.ifEmpty { profileRepository.loadAllProfiles() }
                val bundle = profiles.firstOrNull { it.profile.id == settings.activeProfileId } ?: profiles.first()
                val rootInfo = rootProber.probe()
                val pkg = _state.value.packageName
                val prefsPath = rootBackend.resolveSharedPrefsPath(rootInfo.activeUserId, pkg, bundle.profile)
                val xml = rootBackend.readBytes(prefsPath).getOrThrow().toString(Charsets.UTF_8)
                val cleaned = withContext(Dispatchers.Default) {
                    rustCore.sanitizePlayerPrefs(xml, bundle.profile.sanitizer)
                }
                if (cleaned.removedKeys.isNotEmpty()) {
                    rootBackend.writeBytesTransactional(
                        path = prefsPath,
                        bytes = cleaned.xml.toByteArray(Charsets.UTF_8),
                        packageNameToStop = pkg,
                        createRollback = true
                    ).getOrThrow()
                }
                cleaned.removedKeys.size
            }.onSuccess { count ->
                refresh()
                _state.update { it.copy(statusMessage = "Rust Core đã xóa $count khóa định danh tài khoản khỏi playerprefs.xml!") }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, errorMessage = "Lỗi xóa tài khoản: ${e.message}") }
            }
        }
    }

    fun applyNewSsaid(newSsaidHex: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            runCatching {
                val targetSsaid = newSsaidHex?.trim()?.ifBlank { null }
                    ?: withContext(Dispatchers.Default) { rustCore.generateRandomSsaid() }
                val rootInfo = rootProber.probe()
                val s = _state.value
                val ssaidPath = s.ssaidFilePath.ifBlank { rootBackend.resolveSsaidPath(rootInfo.activeUserId) }
                val originalBytes = rootBackend.readBytes(ssaidPath).getOrThrow()

                val origBackup = storagePaths.getSsaidBackupFile(rootInfo.activeUserId)
                if (!origBackup.exists()) {
                    withContext(Dispatchers.IO) { origBackup.writeBytes(originalBytes) }
                }

                // Rust ABX/XML update + strict in-memory round-trip verification!
                val verified = withContext(Dispatchers.Default) {
                    rustCore.updateSsaidVerified(
                        originalBytes = originalBytes,
                        packageName = s.packageName,
                        newSsaid = targetSsaid
                    )
                }

                rootBackend.writeBytesTransactional(
                    path = ssaidPath,
                    bytes = verified.updatedBytes,
                    packageNameToStop = s.packageName,
                    createRollback = true
                ).getOrThrow()

                verified.meta
            }.onSuccess { meta ->
                refresh()
                _state.update {
                    it.copy(
                        statusMessage = "Rust ABX/XML đã đổi SSAID (${meta.format.name}): ${meta.previousValue} → ${meta.newValue} (kiểm chứng ${meta.verifiedEntryCount} gói). Hãy chạy Soft Reboot để áp dụng!"
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, errorMessage = "Đổi SSAID thất bại: ${e.message}") }
            }
        }
    }

    fun restoreOriginalSsaid() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            runCatching {
                val rootInfo = rootProber.probe()
                val origBackup = storagePaths.getSsaidBackupFile(rootInfo.activeUserId)
                check(origBackup.exists()) { "Chưa có bản sao lưu SSAID gốc" }
                val bytes = withContext(Dispatchers.IO) { origBackup.readBytes() }
                withContext(Dispatchers.Default) { rustCore.inspectSsaid(bytes, _state.value.packageName) }
                val ssaidPath = rootBackend.resolveSsaidPath(rootInfo.activeUserId)
                rootBackend.writeBytesTransactional(
                    path = ssaidPath,
                    bytes = bytes,
                    packageNameToStop = _state.value.packageName,
                    createRollback = false
                ).getOrThrow()
            }.onSuccess {
                refresh()
                _state.update { it.copy(statusMessage = "Đã khôi phục tệp settings_ssaid.xml gốc!") }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, errorMessage = "Khôi phục SSAID thất bại: ${e.message}") }
            }
        }
    }

    fun softReboot() {
        viewModelScope.launch {
            rootBackend.softRebootSystemServer()
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
        }
    }

    fun fullReboot() {
        viewModelScope.launch {
            rootBackend.fullRebootDevice()
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
        }
    }
}
