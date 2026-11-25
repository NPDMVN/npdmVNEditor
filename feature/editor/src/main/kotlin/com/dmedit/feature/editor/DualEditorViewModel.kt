package com.dmedit.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmedit.core.model.DataFormat
import com.dmedit.core.model.DiffEntry
import com.dmedit.core.model.GameProfile
import com.dmedit.core.model.ItemCatalog
import com.dmedit.core.model.VisualSchema
import com.dmedit.core.nativebridge.RustCoreEngine
import com.dmedit.core.nativebridge.RustDocumentSession
import com.dmedit.core.shell.AndroidRootBackend
import com.dmedit.core.shell.NewFormatDiagnostic
import com.dmedit.core.shell.ProfileRepository
import com.dmedit.core.shell.SettingsRepository
import com.dmedit.core.shell.SoulKnightFormatSyncer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File
import javax.inject.Inject

enum class EditorDisplayMode {
    VISUAL,
    RAW,
    SPLIT
}

data class EditorUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isRepairingFormat: Boolean = false,
    val fileName: String = "",
    val filePath: String = "",
    val archivePath: String? = null,
    val archiveEntryPath: String? = null,
    val packageName: String = "",
    val codecId: String = "plain",
    val format: DataFormat = DataFormat.TEXT,
    val rawText: String = "",
    val jsonRoot: JsonElement? = null,
    val visualSchema: VisualSchema? = null,
    val itemCatalog: ItemCatalog? = null,
    val availablePresetKeys: Set<String> = emptySet(),
    val displayMode: EditorDisplayMode = EditorDisplayMode.VISUAL,
    val isDirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val validationError: String? = null,
    val showDiffDialog: Boolean = false,
    val pendingDiffs: List<DiffEntry> = emptyList(),
    val showFindReplace: Boolean = false,
    val lastRollbackPath: String? = null,
    val newFormatConflict: NewFormatDiagnostic? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val revision: Long = 0L
) {
    val isModernFormatFile: Boolean
        get() = fileName.endsWith(".new", ignoreCase = true) || fileName.endsWith(".rij", ignoreCase = true)
}

@HiltViewModel
class DualEditorViewModel @Inject constructor(
    private val rustCore: RustCoreEngine,
    private val rootBackend: AndroidRootBackend,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val formatSyncer: SoulKnightFormatSyncer
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val sessionMutex = Mutex()
    private var session: RustDocumentSession? = null
    private var activeProfile: GameProfile? = null
    private var originalRawBytes: ByteArray? = null
    private var presetPack: Map<String, JsonElement> = emptyMap()
    private var lastOpenProfileId: String = ""
    private var lastOpenIsWideScreen: Boolean = false

    fun openFile(
        filePath: String,
        profileId: String,
        packageName: String,
        isWideScreen: Boolean,
        archivePath: String? = null,
        archiveEntryPath: String? = null
    ) {
        lastOpenProfileId = profileId
        lastOpenIsWideScreen = isWideScreen
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            try {
                val profiles = profileRepository.profiles.value.ifEmpty { profileRepository.loadAllProfiles() }
                val bundle = profiles.firstOrNull { it.profile.id == profileId }
                    ?: profiles.firstOrNull()
                    ?: error("Không tìm thấy Game Profile '$profileId'")
                val profile = bundle.profile
                activeProfile = profile

                val fileName = File(archiveEntryPath ?: filePath).name
                val matchedRule = withContext(Dispatchers.Default) {
                    rustCore.matchFileRule(profile, fileName)
                }
                val codecId = matchedRule?.codec ?: "plain"
                val format = matchedRule?.format ?: inferFormatFromName(fileName)

                val rawBytes = if (archivePath != null && archiveEntryPath != null) {
                    withContext(Dispatchers.IO) {
                        rustCore.verifyAndReadEntry(File(archivePath), archiveEntryPath)
                    }
                } else {
                    rootBackend.readBytes(filePath).getOrThrow()
                }
                originalRawBytes = rawBytes

                val hasKey = withContext(Dispatchers.Default) {
                    rustCore.hasValidCodecKey(profile, codecId)
                }

                val decodedText = withContext(Dispatchers.Default) {
                    val effectiveCodecId = if (hasKey) codecId else "plain"
                    val plainBytes = rustCore.decryptBytes(profile, effectiveCodecId, rawBytes)
                    val rawDecoded = plainBytes.toString(Charsets.UTF_8)
                    if (format == DataFormat.JSON) {
                        rustCore.beautifyJson(rawDecoded).getOrDefault(rawDecoded)
                    } else {
                        rawDecoded
                    }
                }

                val docSession = RustDocumentSession(rustCore, fileName, format, decodedText)
                session = docSession

                val schema = matchedRule?.visualSchema?.let {
                    profileRepository.loadVisualSchema(bundle, it)
                }
                val catalog = schema?.catalogRef?.let {
                    profileRepository.loadItemCatalog(bundle, it)
                }
                val presets = schema?.presetRef?.let {
                    profileRepository.loadPresetPack(bundle, it)
                }.orEmpty()
                presetPack = presets

                val defaultMode = when {
                    format != DataFormat.JSON -> EditorDisplayMode.RAW
                    isWideScreen -> EditorDisplayMode.SPLIT
                    else -> EditorDisplayMode.VISUAL
                }

                val keyWarning = if (codecId != "plain" && !hasKey) {
                    "Codec '$codecId' chưa cấu hình khóa trong profile — đang mở ở chế độ raw."
                } else null

                val conflict = if (archivePath == null && isSoulKnightTarget(profile, filePath)) {
                    runCatching {
                        formatSyncer.inspectNewFormatConflict(profile, packageName, filePath)
                    }.getOrNull()
                } else {
                    null
                }

                _uiState.update {
                    EditorUiState(
                        isLoading = false,
                        fileName = fileName,
                        filePath = filePath,
                        archivePath = archivePath,
                        archiveEntryPath = archiveEntryPath,
                        packageName = packageName,
                        codecId = codecId,
                        format = format,
                        rawText = docSession.currentText,
                        jsonRoot = docSession.currentJson,
                        visualSchema = schema,
                        itemCatalog = catalog,
                        availablePresetKeys = presets.keys,
                        displayMode = defaultMode,
                        isDirty = docSession.isDirty,
                        canUndo = docSession.canUndo,
                        canRedo = docSession.canRedo,
                        newFormatConflict = conflict,
                        statusMessage = keyWarning,
                        revision = 1L
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Lỗi mở tệp: ${e.message}")
                }
            }
        }
    }

    fun reloadFromDisk() {
        val state = _uiState.value
        if (state.filePath.isBlank()) return
        openFile(
            filePath = state.filePath,
            profileId = lastOpenProfileId.ifBlank { activeProfile?.id ?: "chillyroom.soul_knight" },
            packageName = state.packageName,
            isWideScreen = lastOpenIsWideScreen,
            archivePath = state.archivePath,
            archiveEntryPath = state.archiveEntryPath
        )
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun dismissErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setDisplayMode(mode: EditorDisplayMode) {
        _uiState.update { it.copy(displayMode = mode) }
    }

    fun toggleFindReplace() {
        _uiState.update { it.copy(showFindReplace = !it.showFindReplace) }
    }

    fun onRawTextChanged(newText: String) {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                withContext(Dispatchers.Default) { s.updateRawText(newText, pushHistory = true) }
                syncStateFromSessionAsync(incrementRevision = false)
            }
        }
    }

    fun onVisualPatchField(jsonPath: String, newValue: JsonElement) {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                runCatching { withContext(Dispatchers.Default) { s.patchJsonPath(jsonPath, newValue) } }
                    .onSuccess { changed -> if (changed) syncStateFromSessionAsync(incrementRevision = true) }
                    .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
            }
        }
    }

    fun onVisualPatchMapEntry(mapPath: String, key: String, newValue: JsonElement) {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                runCatching { withContext(Dispatchers.Default) { s.patchMapEntry(mapPath, key, newValue) } }
                    .onSuccess { changed -> if (changed) syncStateFromSessionAsync(incrementRevision = true) }
                    .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
            }
        }
    }

    fun onVisualBatchMap(mapPath: String, newValue: JsonElement) {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                runCatching { withContext(Dispatchers.Default) { s.batchSetMap(mapPath, newValue) } }
                    .onSuccess { changed ->
                        if (changed) {
                            syncStateFromSessionAsync(
                                incrementRevision = true,
                                status = "Đã cập nhật hàng loạt thành công!"
                            )
                        }
                    }
                    .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
            }
        }
    }

    fun onVisualBatchNestedPairs(
        mapPath: String,
        groupKey: String?,
        valueField: String,
        newValue: JsonElement
    ) {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                runCatching {
                    withContext(Dispatchers.Default) {
                        s.batchUpdateNestedPairs(mapPath, groupKey, valueField, newValue)
                    }
                }
                    .onSuccess { changed ->
                        if (changed) {
                            syncStateFromSessionAsync(
                                incrementRevision = true,
                                status = if (groupKey == null) "Đã mở khóa hàng loạt toàn bộ!" else "Đã cập nhật $groupKey!"
                            )
                        }
                    }
                    .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
            }
        }
    }

    fun onApplyPresetMerge(
        targetPath: String,
        presetKey: String,
        legacyPresetKey: String?,
        dedupeKey: String?,
        overwriteExisting: Boolean,
        label: String
    ) {
        val s = session ?: return
        val state = _uiState.value
        val effectiveKey = if (!state.isModernFormatFile && !legacyPresetKey.isNullOrBlank() && legacyPresetKey in presetPack) {
            legacyPresetKey
        } else {
            presetKey
        }
        val presetElement = presetPack[effectiveKey]
        if (presetElement == null) {
            _uiState.update { it.copy(errorMessage = "Không tìm thấy gói preset '$effectiveKey'") }
            return
        }

        viewModelScope.launch {
            sessionMutex.withLock {
                runCatching {
                    withContext(Dispatchers.Default) {
                        s.mergeJsonPreset(
                            targetPath = targetPath,
                            presetElement = presetElement,
                            overwriteExisting = overwriteExisting,
                            dedupeKey = dedupeKey
                        )
                    }
                }
                    .onSuccess { changed ->
                        syncStateFromSessionAsync(
                            incrementRevision = changed,
                            status = if (changed) {
                                "✓ Đã áp dụng: $label — hãy bấm Lưu (Save) để ghi xuống đĩa!"
                            } else {
                                "Đã có đầy đủ toàn bộ mục của '$label'!"
                            }
                        )
                    }
                    .onFailure { e -> _uiState.update { it.copy(errorMessage = "Áp dụng preset thất bại: ${e.message}") } }
            }
        }
    }

    fun undo() {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                val ok = withContext(Dispatchers.Default) { s.undo() }
                if (ok) syncStateFromSessionAsync(incrementRevision = true)
            }
        }
    }

    fun redo() {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                val ok = withContext(Dispatchers.Default) { s.redo() }
                if (ok) syncStateFromSessionAsync(incrementRevision = true)
            }
        }
    }

    fun beautifyJson() {
        val s = session ?: return
        viewModelScope.launch {
            sessionMutex.withLock {
                val res = withContext(Dispatchers.Default) { s.beautify() }
                res.onSuccess { syncStateFromSessionAsync(incrementRevision = true, status = "Đã định dạng JSON (Rust Core)") }
                    .onFailure { e -> _uiState.update { it.copy(errorMessage = "JSON không hợp lệ: ${e.message}") } }
            }
        }
    }

    fun requestSave() {
        val s = session ?: return
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            val validation = withContext(Dispatchers.Default) { s.validate() }
            if (!validation.isValid) {
                _uiState.update { it.copy(validationError = validation.errorMessage, errorMessage = validation.errorMessage) }
                return@launch
            }
            val settings = settingsRepository.settings.first()
            if (settings.showDiffBeforeSave) {
                val diffs = withContext(Dispatchers.Default) { s.computeDiffs() }
                _uiState.update { it.copy(showDiffDialog = true, pendingDiffs = diffs, validationError = null) }
            } else {
                commitSave()
            }
        }
    }

    fun dismissDiffDialog() {
        _uiState.update { it.copy(showDiffDialog = false) }
    }

    fun commitSave() {
        val s = session ?: return
        val profile = activeProfile ?: return
        val state = _uiState.value
        if (state.isSaving) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    showDiffDialog = false,
                    errorMessage = null,
                    statusMessage = "Đang mã hóa & xác thực ghi đĩa..."
                )
            }
            try {
                val settings = settingsRepository.settings.first()
                val encodedBytes = withContext(Dispatchers.Default) {
                    val textToEncode = if (state.format == DataFormat.JSON && state.codecId != "plain") {
                        rustCore.minifyJson(s.currentText).getOrDefault(s.currentText)
                    } else {
                        s.currentText
                    }
                    val plainBytes = textToEncode.toByteArray(Charsets.UTF_8)
                    val effectiveId = if (rustCore.hasValidCodecKey(profile, state.codecId)) {
                        state.codecId
                    } else {
                        "plain"
                    }
                    rustCore.encryptBytes(profile, effectiveId, plainBytes, originalRawBytes)
                }

                if (state.archivePath != null && state.archiveEntryPath != null) {
                    withContext(Dispatchers.IO) {
                        rustCore.updateBackupEntry(
                            File(state.archivePath),
                            state.archiveEntryPath,
                            encodedBytes
                        )
                    }
                    s.markSaved()
                    originalRawBytes = encodedBytes
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isDirty = s.isDirty,
                            statusMessage = "✓ Đã lưu trực tiếp vào tệp backup .skdb (${encodedBytes.size} bytes)!"
                        )
                    }
                } else {
                    val pkgToStop = if (settings.forceStopGameBeforeWrite) state.packageName else null
                    val rollbackFile = rootBackend.writeBytesTransactional(
                        path = state.filePath,
                        bytes = encodedBytes,
                        packageNameToStop = pkgToStop,
                        createRollback = settings.autoBackupBeforeSave
                    ).getOrThrow()

                    s.markSaved()
                    originalRawBytes = encodedBytes

                    var prefsSyncNote = ""
                    var prefsSyncErr: String? = null
                    val isSkGameData = isSoulKnightTarget(profile, state.filePath) &&
                        state.fileName.startsWith("game", ignoreCase = true) &&
                        state.fileName.endsWith(".data", ignoreCase = true)

                    if (isSkGameData) {
                        formatSyncer.syncGameDataToPlayerPrefs(
                            gameJsonText = s.currentText,
                            profile = profile,
                            packageName = state.packageName,
                            currentFilePath = state.filePath
                        ).onSuccess { syncRes ->
                            val accs = syncRes.syncedAccounts.joinToString(", ")
                            prefsSyncNote = if (syncRes.updatedKeysCount > 0) {
                                " + đã đồng bộ ${syncRes.updatedKeysCount} khóa vào playerprefs.xml (UID: $accs)"
                            } else {
                                " + playerprefs.xml (UID: $accs) đã khớp"
                            }
                        }.onFailure { err ->
                            prefsSyncErr = "Đã lưu ${state.fileName} nhưng đồng bộ playerprefs.xml thất bại: ${err.message}"
                        }
                    }

                    val postStat = runCatching { rootBackend.statFile(state.filePath) }.getOrNull()
                    val refreshedConflict = if (isSoulKnightTarget(profile, state.filePath)) {
                        runCatching {
                            formatSyncer.inspectNewFormatConflict(profile, state.packageName, state.filePath)
                        }.getOrNull()
                    } else {
                        null
                    }
                    val statSuffix = postStat?.let { " • uid=${it.uid} • mode=${it.modeOctal}" } ?: ""

                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isDirty = s.isDirty,
                            lastRollbackPath = rollbackFile?.absolutePath,
                            newFormatConflict = refreshedConflict,
                            errorMessage = prefsSyncErr,
                            statusMessage = "✓ Đã mã hóa (${state.codecId.uppercase()}) & xác thực ghi đĩa (${encodedBytes.size} bytes$statSuffix)$prefsSyncNote!"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        statusMessage = null,
                        errorMessage = "Lưu thất bại: ${e.message}"
                    )
                }
            }
        }
    }

    fun restoreMovedNewFormat() {
        val profile = activeProfile ?: return
        val state = _uiState.value
        if (state.isRepairingFormat) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRepairingFormat = true,
                    errorMessage = null,
                    statusMessage = "Đang khôi phục các tệp .data.new từ backup..."
                )
            }
            formatSyncer.restoreLatestMovedNewFormat(profile, state.packageName, state.filePath)
                .onSuccess { res ->
                    val refreshed = runCatching {
                        formatSyncer.inspectNewFormatConflict(profile, state.packageName, state.filePath)
                    }.getOrNull()
                    _uiState.update {
                        it.copy(
                            isRepairingFormat = false,
                            newFormatConflict = refreshed,
                            statusMessage = "✓ Đã khôi phục ${res.restoredFilesCount} tệp .data.new và bật lại ${res.enabledFlagsCount} cờ OpenRijTest!"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isRepairingFormat = false,
                            statusMessage = null,
                            errorMessage = "Khôi phục .data.new thất bại: ${e.message}"
                        )
                    }
                }
        }
    }

    fun switchToClassicSaveFormat() {
        val profile = activeProfile ?: return
        val state = _uiState.value
        if (state.isRepairingFormat) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRepairingFormat = true,
                    errorMessage = null,
                    statusMessage = "Đang sao lưu .data.new/.data.rij và chuyển sang Classic Save..."
                )
            }
            formatSyncer.switchToClassicFormat(profile, state.packageName, state.filePath)
                .onSuccess { res ->
                    val refreshed = runCatching {
                        formatSyncer.inspectNewFormatConflict(profile, state.packageName, state.filePath)
                    }.getOrNull()
                    _uiState.update {
                        it.copy(
                            isRepairingFormat = false,
                            newFormatConflict = refreshed,
                            statusMessage = "✓ Đã chuyển Classic Save (di chuyển ${res.movedFilesCount} tệp vào backup, tắt ${res.clearedFlagsCount} cờ OpenRijTest). Hãy mở lại game!"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isRepairingFormat = false,
                            statusMessage = null,
                            errorMessage = "Chuyển Classic Save thất bại: ${e.message}"
                        )
                    }
                }
        }
    }

    fun rollbackLastSave() {
        val rollbackPath = _uiState.value.lastRollbackPath ?: return
        val targetPath = _uiState.value.filePath
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching {
                val bytes = withContext(Dispatchers.IO) { File(rollbackPath).readBytes() }
                rootBackend.writeBytesTransactional(targetPath, bytes, _uiState.value.packageName, false).getOrThrow()
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false) }
                reloadFromDisk()
                _uiState.update { it.copy(statusMessage = "✓ Đã khôi phục bản gốc trước khi lưu!") }
            }.onFailure { e ->
                _uiState.update { it.copy(isSaving = false, errorMessage = "Rollback thất bại: ${e.message}") }
            }
        }
    }

    private suspend fun syncStateFromSessionAsync(incrementRevision: Boolean, status: String? = null) {
        val s = session ?: return
        val valRes = withContext(Dispatchers.Default) { runCatching { s.validate() }.getOrNull() }
        val invalidErr = if (valRes?.isValid == false) valRes.errorMessage else null
        _uiState.update { prev ->
            prev.copy(
                rawText = s.currentText,
                jsonRoot = s.currentJson,
                isDirty = s.isDirty,
                canUndo = s.canUndo,
                canRedo = s.canRedo,
                validationError = invalidErr,
                errorMessage = invalidErr,
                statusMessage = status ?: prev.statusMessage,
                revision = if (incrementRevision) prev.revision + 1 else prev.revision
            )
        }
    }

    private fun isSoulKnightTarget(profile: GameProfile, filePath: String): Boolean =
        profile.id == "chillyroom.soul_knight" || filePath.contains("DungeonShooter", ignoreCase = true)

    private fun inferFormatFromName(name: String): DataFormat = when {
        name.endsWith(".xml", ignoreCase = true) -> DataFormat.XML
        name.endsWith(".json", ignoreCase = true) || name.endsWith(".data", ignoreCase = true) -> DataFormat.JSON
        else -> DataFormat.TEXT
    }
}
