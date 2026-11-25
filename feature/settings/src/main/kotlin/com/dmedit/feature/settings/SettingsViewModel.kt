package com.dmedit.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmedit.core.model.AppSettings
import com.dmedit.core.model.RootEnvironmentInfo
import com.dmedit.core.nativebridge.RustCoreEngine
import com.dmedit.core.shell.AdaptiveRootProber
import com.dmedit.core.shell.AppLanguage
import com.dmedit.core.shell.AppStoragePaths
import com.dmedit.core.shell.LoadedProfileBundle
import com.dmedit.core.shell.PresetOverrideInfo
import com.dmedit.core.shell.ProfileRepository
import com.dmedit.core.shell.SettingsRepository
import com.dmedit.core.shell.SoulKnightAdvancedTools
import com.dmedit.core.shell.SoulKnightQuickPatchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val rootInfo: RootEnvironmentInfo = RootEnvironmentInfo(),
    val rustCoreBanner: String = "",
    val profiles: List<LoadedProfileBundle> = emptyList(),
    val presetOverrideInfo: PresetOverrideInfo = PresetOverrideInfo(),
    val isBusyPreset: Boolean = false,
    val latestCrashLog: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rustCore: RustCoreEngine,
    private val settingsRepository: SettingsRepository,
    private val rootProber: AdaptiveRootProber,
    private val profileRepository: ProfileRepository,
    private val storagePaths: AppStoragePaths,
    private val quickEngine: SoulKnightQuickPatchEngine,
    private val advancedTools: SoulKnightAdvancedTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { s ->
                _uiState.update { it.copy(settings = s) }
            }
        }
        viewModelScope.launch {
            profileRepository.profiles.collectLatest { list ->
                _uiState.update { it.copy(profiles = list) }
            }
        }
        refreshDiagnostics()
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val s = _uiState.value.settings
            val info = rootProber.probe(s.useCustomToybox, s.customToyboxCommand, forceReprobe = true)
            profileRepository.loadAllProfiles()
            val logFile = storagePaths.getLatestLogFile()
            val logText = withContext(Dispatchers.IO) {
                if (logFile.exists()) logFile.readText(Charsets.UTF_8).takeLast(8_000) else null
            }
            val presetInfo = profileRepository.getPresetOverrideInfo("chillyroom.soul_knight")
            _uiState.update {
                it.copy(
                    rootInfo = info,
                    rustCoreBanner = rustCore.coreBanner(),
                    presetOverrideInfo = presetInfo,
                    latestCrashLog = logText
                )
            }
        }
    }

    fun toggleFilterFiles(enabled: Boolean) = viewModelScope.launch { settingsRepository.setFilterGameFiles(enabled) }
    fun toggleForceStop(enabled: Boolean) = viewModelScope.launch { settingsRepository.setForceStopBeforeWrite(enabled) }
    fun toggleAutoBackup(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoBackupBeforeSave(enabled) }
    fun toggleShowDiff(enabled: Boolean) = viewModelScope.launch { settingsRepository.setShowDiffBeforeSave(enabled) }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { settingsRepository.setLanguage(language) }

    fun updateCustomToybox(enabled: Boolean, command: String) {
        viewModelScope.launch {
            settingsRepository.setCustomToybox(enabled, command)
            refreshDiagnostics()
        }
    }

    fun importExternalProfile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri).orEmpty()
                val isZip = mime.contains("zip") || uri.toString().endsWith(".zip", ignoreCase = true)
                if (isZip) {
                    resolver.openInputStream(uri)?.use { profileRepository.importProfileZip(it).getOrThrow() }
                        ?: error("Không đọc được tệp zip")
                } else {
                    val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Không đọc được tệp JSON")
                    profileRepository.importProfileJson(text).getOrThrow()
                }
            }.onSuccess { profile ->
                _uiState.update { it.copy(statusMessage = "Đã nạp Game Profile: ${profile.displayName} (${profile.id})") }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "Nạp profile thất bại: ${e.message}") }
            }
        }
    }

    fun importSoulKnightPresetJson(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusyPreset = true, errorMessage = null, statusMessage = null) }
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("Không đọc được tệp Preset JSON")
                profileRepository.importPresetOverrideJson("chillyroom.soul_knight", raw).getOrThrow()
            }.onSuccess { info ->
                _uiState.update {
                    it.copy(
                        isBusyPreset = false,
                        presetOverrideInfo = info,
                        statusMessage = "✓ Đã nạp Preset mở rộng (${info.presetGroupsCount} nhóm / ${info.totalEntriesCount} mục) — áp dụng ngay không cần build lại APK!"
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isBusyPreset = false,
                        errorMessage = "Nạp Preset thất bại: ${e.message}"
                    )
                }
            }
        }
    }

    fun extractPresetsFromLiveSoulKnightSave() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusyPreset = true, errorMessage = null, statusMessage = "Đang quét save Soul Knight trên máy để trích xuất vật phẩm mới...") }
            val settings = settingsRepository.settings.first()
            val scan = quickEngine.scan(packageOverride = settings.activePackageOverride)
            advancedTools.extractPresetsFromLiveSave(scan)
                .onSuccess { res ->
                    _uiState.update {
                        it.copy(
                            isBusyPreset = false,
                            presetOverrideInfo = res.overrideInfo,
                            statusMessage = "✓ Đã trích xuất ${res.extractedGroups} nhóm (${res.details.joinToString(", ")}) vào gói Preset cục bộ!"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isBusyPreset = false,
                            statusMessage = null,
                            errorMessage = "Trích xuất Preset thất bại: ${e.message}"
                        )
                    }
                }
        }
    }

    fun resetSoulKnightPresetOverride() {
        viewModelScope.launch {
            profileRepository.clearPresetOverride("chillyroom.soul_knight")
            val info = profileRepository.getPresetOverrideInfo("chillyroom.soul_knight")
            _uiState.update {
                it.copy(
                    presetOverrideInfo = info,
                    statusMessage = "✓ Đã khôi phục gói Preset mặc định tích hợp (8.8.0)."
                )
            }
        }
    }
}
