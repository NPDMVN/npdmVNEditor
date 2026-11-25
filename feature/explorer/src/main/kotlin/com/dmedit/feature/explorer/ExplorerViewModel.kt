package com.dmedit.feature.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmedit.core.model.AppSettings
import com.dmedit.core.model.RootEnvironmentInfo
import com.dmedit.core.model.SaveFileEntry
import com.dmedit.core.shell.AdaptiveRootProber
import com.dmedit.core.shell.AndroidRootBackend
import com.dmedit.core.shell.LoadedProfileBundle
import com.dmedit.core.shell.ProfileRepository
import com.dmedit.core.shell.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExplorerUiState(
    val isLoading: Boolean = true,
    val rootInfo: RootEnvironmentInfo = RootEnvironmentInfo(),
    val profiles: List<LoadedProfileBundle> = emptyList(),
    val activeBundle: LoadedProfileBundle? = null,
    val activePackage: String = "",
    val installedPackages: List<String> = emptyList(),
    val currentDirPath: String = "",
    val entries: List<SaveFileEntry> = emptyList(),
    val searchQuery: String = "",
    val filterGameFiles: Boolean = true,
    val errorMessage: String? = null,
    val statusMessage: String? = null
) {
    val visibleEntries: List<SaveFileEntry>
        get() = entries.filter { entry ->
            val matchesFilter = !filterGameFiles || entry.stat.isDirectory || entry.isGameRelevant
            val matchesSearch = searchQuery.isBlank() ||
                entry.name.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
}

@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val rootProber: AdaptiveRootProber,
    private val rootBackend: AndroidRootBackend,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll(forceReprobe: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val settings: AppSettings = settingsRepository.settings.first()
            val profiles = profileRepository.loadAllProfiles()
            val rootInfo = rootProber.probe(
                useCustomToybox = settings.useCustomToybox,
                customToyboxCommand = settings.customToyboxCommand,
                forceReprobe = forceReprobe
            )

            val installed = if (rootInfo.hasRoot) rootBackend.listInstalledPackages() else emptyList()
            val activeBundle = profiles.firstOrNull { it.profile.id == settings.activeProfileId }
                ?: profiles.firstOrNull()

            val resolvedPkg = resolvePackageForProfile(activeBundle, settings.activePackageOverride, installed)
            val startDir = if (activeBundle != null && resolvedPkg.isNotBlank() && rootInfo.hasRoot) {
                rootBackend.resolveGameFilesDir(rootInfo.activeUserId, resolvedPkg, activeBundle.profile)
            } else {
                rootInfo.sharedFolderPath ?: "/sdcard/Download"
            }

            _uiState.update {
                it.copy(
                    rootInfo = rootInfo,
                    profiles = profiles,
                    activeBundle = activeBundle,
                    activePackage = resolvedPkg,
                    installedPackages = installed,
                    filterGameFiles = settings.filterGameFiles,
                    currentDirPath = startDir
                )
            }

            if (activeBundle != null) {
                loadDirectory(startDir, activeBundle)
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Không tìm thấy Game Profile nào.") }
            }
        }
    }

    fun selectProfile(bundle: LoadedProfileBundle, packageOverride: String? = null) {
        viewModelScope.launch {
            val state = _uiState.value
            val pkg = resolvePackageForProfile(bundle, packageOverride, state.installedPackages)
            settingsRepository.setActiveProfile(bundle.profile.id, packageOverride)
            val dir = if (state.rootInfo.hasRoot && pkg.isNotBlank()) {
                rootBackend.resolveGameFilesDir(state.rootInfo.activeUserId, pkg, bundle.profile)
            } else {
                state.rootInfo.sharedFolderPath ?: "/sdcard/Download"
            }
            _uiState.update {
                it.copy(activeBundle = bundle, activePackage = pkg, currentDirPath = dir, errorMessage = null)
            }
            loadDirectory(dir, bundle)
        }
    }

    fun openDirectory(path: String) {
        val bundle = _uiState.value.activeBundle ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(currentDirPath = path, errorMessage = null) }
            loadDirectory(path, bundle)
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentDirPath.trimEnd('/')
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotBlank()) {
            openDirectory(parent)
        }
    }

    fun jumpToGameFilesDir() {
        val s = _uiState.value
        val bundle = s.activeBundle ?: return
        if (s.activePackage.isBlank()) return
        openDirectory(rootBackend.resolveGameFilesDir(s.rootInfo.activeUserId, s.activePackage, bundle.profile))
    }

    fun jumpToSharedPrefsDir() {
        val s = _uiState.value
        if (s.activePackage.isBlank()) return
        openDirectory("${rootBackend.resolveGameDataRoot(s.rootInfo.activeUserId, s.activePackage)}/shared_prefs")
    }

    fun jumpToSharedEmulatorFolder() {
        val folder = _uiState.value.rootInfo.sharedFolderPath ?: "/sdcard/Pictures"
        openDirectory(folder)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleFilterGameFiles() {
        val next = !_uiState.value.filterGameFiles
        _uiState.update { it.copy(filterGameFiles = next) }
        viewModelScope.launch { settingsRepository.setFilterGameFiles(next) }
    }

    fun deleteEntry(entry: SaveFileEntry) {
        viewModelScope.launch {
            rootBackend.deleteFile(entry.fullPath)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "Đã xóa ${entry.name}") }
                    openDirectory(_uiState.value.currentDirPath)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message) }
                }
        }
    }

    fun renameEntry(entry: SaveFileEntry, newName: String) {
        if (newName.isBlank() || newName.contains('/')) return
        viewModelScope.launch {
            val parent = entry.fullPath.substringBeforeLast('/')
            val newPath = "$parent/$newName"
            rootBackend.renameFile(entry.fullPath, newPath)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "Đã đổi tên thành $newName") }
                    openDirectory(_uiState.value.currentDirPath)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = e.message) }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private suspend fun loadDirectory(dirPath: String, bundle: LoadedProfileBundle) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        if (!_uiState.value.rootInfo.hasRoot && dirPath.startsWith("/data/")) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    entries = emptyList(),
                    errorMessage = it.rootInfo.diagnosticMessage
                )
            }
            return
        }

        rootBackend.listDirectory(dirPath, bundle.profile)
            .onSuccess { list ->
                _uiState.update { it.copy(isLoading = false, entries = list) }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        entries = emptyList(),
                        errorMessage = e.message ?: "Không đọc được thư mục"
                    )
                }
            }
    }

    private fun resolvePackageForProfile(
        bundle: LoadedProfileBundle?,
        overridePkg: String?,
        installed: List<String>
    ): String {
        if (!overridePkg.isNullOrBlank()) return overridePkg
        val profile = bundle?.profile ?: return ""
        val installedMatch = profile.packages.firstOrNull { it in installed }
        return installedMatch ?: profile.packages.firstOrNull().orEmpty()
    }
}
