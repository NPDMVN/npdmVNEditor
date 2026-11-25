package com.dmedit.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmedit.core.nativebridge.RustCoreEngine
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.AppStoragePaths
import com.dmedit.core.shell.QuickModPlan
import com.dmedit.core.shell.QuickModScanResult
import com.dmedit.core.shell.QuickRollbackSession
import com.dmedit.core.shell.SaveHealthReport
import com.dmedit.core.shell.SettingsRepository
import com.dmedit.core.shell.SoulKnightAdvancedTools
import com.dmedit.core.shell.SoulKnightQuickPatchEngine
import com.dmedit.core.shell.bi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class QuickModUiState(
    val isScanning: Boolean = true,
    val isApplying: Boolean = false,
    val isBusyTool: Boolean = false,
    val scan: QuickModScanResult = QuickModScanResult(),
    val plan: QuickModPlan = QuickModPlan(),
    val progressLogs: List<String> = emptyList(),
    val rollbackHistory: List<QuickRollbackSession> = emptyList(),
    val availableSkdbFiles: List<File> = emptyList(),
    val healthReport: SaveHealthReport? = null,
    val lastApplySucceeded: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class QuickModViewModel @Inject constructor(
    private val engine: SoulKnightQuickPatchEngine,
    private val advancedTools: SoulKnightAdvancedTools,
    private val settingsRepository: SettingsRepository,
    private val storagePaths: AppStoragePaths
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickModUiState())
    val uiState: StateFlow<QuickModUiState> = _uiState.asStateFlow()

    init {
        rescan()
    }

    fun rescan(preferredUid: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, errorMessage = null) }
            val settings = settingsRepository.settings.first()
            val result = engine.scan(
                preferredUid = preferredUid ?: _uiState.value.scan.selectedUid.takeIf { it.isNotEmpty() },
                packageOverride = settings.activePackageOverride
            )
            val history = engine.loadRollbackHistory()
            val backups = loadSkdbList()
            _uiState.update {
                it.copy(
                    isScanning = false,
                    scan = result,
                    rollbackHistory = history,
                    availableSkdbFiles = backups,
                    errorMessage = if (!result.hasRoot) result.rootMessage else null
                )
            }
        }
    }

    fun selectUid(uid: String) {
        rescan(preferredUid = uid)
    }

    fun updatePlan(transform: (QuickModPlan) -> QuickModPlan) {
        _uiState.update { it.copy(plan = transform(it.plan)) }
    }

    fun toggleHeroSelection(heroKey: String) {
        _uiState.update { st ->
            val cur = st.plan.selectedHeroes
            val next = if (heroKey in cur) cur - heroKey else cur + heroKey
            st.copy(plan = st.plan.copy(selectedHeroes = next))
        }
    }

    fun clearHeroSelection() {
        _uiState.update { st -> st.copy(plan = st.plan.copy(selectedHeroes = emptySet())) }
    }

    fun toggleLanguage() {
        viewModelScope.launch {
            settingsRepository.setLanguage(AppLocaleHolder.current.toggled())
        }
    }

    fun selectNoIapSafe() {
        _uiState.update {
            it.copy(
                plan = QuickModPlan.noIapSafeDefaults(),
                statusMessage = bi(
                    "Đã chọn cấu hình No-IAP (Nick chính): chỉ mở skin mua bằng Gems, không đụng tướng/skin/Cá khô IAP.",
                    "Selected No-IAP (Main Acc): unlocks Gem skins & farmable items/modes only, skipping paid IAP heroes/skins/Fish Chips."
                )
            )
        }
    }

    fun selectCloudSafe() {
        _uiState.update {
            it.copy(
                plan = QuickModPlan.cloudSafeDefaults(),
                statusMessage = bi(
                    "Đã chọn cấu hình Chuẩn Cloud (đầy đủ 100% Skin & tài nguyên ở mức hợp lý, tắt Cá khô).",
                    "Selected Cloud Safe preset (full 100% skins & safe resource bounds, Fish Chips OFF)."
                )
            )
        }
    }

    fun selectAllMax() {
        _uiState.update {
            it.copy(
                plan = it.plan.copy(
                    unlockHeroes = true,
                    maxHeroLevels = true,
                    heroLevelTarget = 7,
                    unlockSkins = true,
                    skipRestrictedSkins = false,
                    gemSkinsOnly = false,
                    selectedHeroes = emptySet(),
                    unlockSkills = true,
                    unlockPets = true,
                    enableRebornCard = true,
                    maxRoomObjects = true,
                    editGems = true,
                    editFishChips = false, // Never auto-enable paid IAP currency in batch select!
                    editSeasonCoins = true,
                    editEscapeMoney = true,
                    fullMaterials = true,
                    fullSeeds = true,
                    fullBlueprints = true,
                    fullTickets = true,
                    unlockGardenAndMoto = true,
                    fullMythicWeapons = true,
                    fullJewelry = true,
                    fullWeaponEvolutions = true,
                    unlockAllForgeWeapons = true,
                    fullSeasonChipsAndSkills = true,
                    expandEscapeStorage = true,
                    fullMonsterRise = true,
                    fullKillEffects = true,
                    unlockAllGameModes = true,
                    unlockEscapeMapsAndBase = true
                ),
                statusMessage = bi(
                    "Đã chọn Full Offline (toàn bộ 42 tướng, 100% skin, vật phẩm & màn chơi — giữ tắt Cá khô).",
                    "Selected Full Offline preset (all 42 heroes, 100% skins, items & modes — Fish Chips kept OFF)."
                )
            )
        }
    }

    fun selectNone() {
        _uiState.update {
            it.copy(
                plan = it.plan.copy(
                    unlockHeroes = false,
                    maxHeroLevels = false,
                    unlockSkins = false,
                    unlockSkills = false,
                    unlockPets = false,
                    enableRebornCard = false,
                    maxRoomObjects = false,
                    editGems = false,
                    editFishChips = false,
                    editSeasonCoins = false,
                    editEscapeMoney = false,
                    fullMaterials = false,
                    fullSeeds = false,
                    fullBlueprints = false,
                    fullTickets = false,
                    unlockGardenAndMoto = false,
                    fullMythicWeapons = false,
                    fullJewelry = false,
                    fullWeaponEvolutions = false,
                    unlockAllForgeWeapons = false,
                    fullSeasonChipsAndSkills = false,
                    expandEscapeStorage = false,
                    fullMonsterRise = false,
                    fullKillEffects = false,
                    unlockAllGameModes = false,
                    unlockEscapeMapsAndBase = false
                )
            )
        }
    }

    fun applySelected() {
        val state = _uiState.value
        if (state.isApplying || state.isScanning || state.isBusyTool) return
        if (state.plan.selectedCount == 0) {
            _uiState.update { it.copy(errorMessage = "Bạn chưa tích chọn mục nào!") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isApplying = true,
                    lastApplySucceeded = false,
                    progressLogs = listOf("Bắt đầu áp dụng ${state.plan.selectedCount} mục cho UID ${state.scan.selectedUid.ifBlank { "Khách" }}..."),
                    statusMessage = null,
                    errorMessage = null
                )
            }

            val res = engine.applyPlan(
                scan = state.scan,
                plan = state.plan,
                onProgress = { msg ->
                    _uiState.update { prev -> prev.copy(progressLogs = prev.progressLogs + msg) }
                }
            )

            val refreshedHistory = engine.loadRollbackHistory()
            val refreshedBackups = loadSkdbList()
            res.onSuccess { applied ->
                val newScan = engine.scan(
                    preferredUid = state.scan.selectedUid.takeIf { it.isNotEmpty() },
                    packageOverride = state.scan.packageName.takeIf { it.isNotEmpty() }
                )
                val bkNote = applied.autoBackupPath?.let { " • Backup: ${File(it).name}" }.orEmpty()
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        scan = newScan,
                        rollbackHistory = refreshedHistory,
                        availableSkdbFiles = refreshedBackups,
                        lastApplySucceeded = true,
                        statusMessage = "✓ Hoàn tất! Đã sửa & kiểm chứng ${applied.updatedFilesCount} tệp$bkNote"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        rollbackHistory = refreshedHistory,
                        availableSkdbFiles = refreshedBackups,
                        errorMessage = "Áp dụng thất bại: ${err.message}"
                    )
                }
            }
        }
    }

    fun rollbackLatestSession() {
        val latest = _uiState.value.rollbackHistory.firstOrNull() ?: return
        rollbackSession(latest)
    }

    fun rollbackSession(session: QuickRollbackSession) {
        val state = _uiState.value
        if (state.isApplying || state.isBusyTool) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusyTool = true,
                    errorMessage = null,
                    statusMessage = "Đang khôi phục phiên '${session.summary}'..."
                )
            }
            engine.rollbackSession(session)
                .onSuccess { count ->
                    val history = engine.loadRollbackHistory()
                    val newScan = engine.scan(
                        preferredUid = state.scan.selectedUid.takeIf { it.isNotEmpty() },
                        packageOverride = state.scan.packageName.takeIf { it.isNotEmpty() }
                    )
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            scan = newScan,
                            rollbackHistory = history,
                            progressLogs = it.progressLogs + "↺ Đã khôi phục nguyên trạng $count tệp (${session.summary}).",
                            statusMessage = "✓ Đã hoàn tác nguyên trạng $count tệp!"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            statusMessage = null,
                            errorMessage = "Hoàn tác thất bại: ${err.message}"
                        )
                    }
                }
        }
    }

    fun runHealthCheck() {
        val state = _uiState.value
        if (state.isApplying || state.isBusyTool) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusyTool = true, errorMessage = null) }
            val report = advancedTools.auditSaveHealth(state.scan)
            _uiState.update { it.copy(isBusyTool = false, healthReport = report) }
        }
    }

    fun repairHealthIssues() {
        val state = _uiState.value
        if (state.isApplying || state.isBusyTool) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusyTool = true, errorMessage = null, statusMessage = "Đang chuẩn hóa cấu trúc Save...") }
            advancedTools.repairSaveHealth(state.scan)
                .onSuccess { res ->
                    val newReport = advancedTools.auditSaveHealth(state.scan)
                    val history = engine.loadRollbackHistory()
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            healthReport = newReport,
                            rollbackHistory = history,
                            progressLogs = it.progressLogs + res.summaryLines,
                            statusMessage = "✓ Đã kiểm tra & chuẩn hóa ${res.fixedFilesCount} tệp!"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            statusMessage = null,
                            errorMessage = "Chuẩn hóa thất bại: ${err.message}"
                        )
                    }
                }
        }
    }

    fun dismissHealthReport() {
        _uiState.update { it.copy(healthReport = null) }
    }

    fun cloneFromLocalUid(sourceUid: String) {
        val state = _uiState.value
        val targetUid = state.scan.selectedUid
        if (state.isApplying || state.isBusyTool || targetUid.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusyTool = true,
                    errorMessage = null,
                    statusMessage = "Đang chuyển save từ UID $sourceUid → UID $targetUid..."
                )
            }
            advancedTools.cloneSaveBetweenUids(state.scan, sourceUid, targetUid)
                .onSuccess { res ->
                    val history = engine.loadRollbackHistory()
                    val newScan = engine.scan(preferredUid = targetUid, packageOverride = state.scan.packageName)
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            scan = newScan,
                            rollbackHistory = history,
                            lastApplySucceeded = true,
                            progressLogs = it.progressLogs + res.logs,
                            statusMessage = "✓ Đã chuyển ${res.copiedFilesCount} tệp & ${res.syncedPrefsKeys} khóa từ UID $sourceUid → UID $targetUid!"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            statusMessage = null,
                            errorMessage = "Chuyển UID thất bại: ${err.message}"
                        )
                    }
                }
        }
    }

    fun importSkdbToCurrentUid(skdbFile: File) {
        val state = _uiState.value
        val targetUid = state.scan.selectedUid
        if (state.isApplying || state.isBusyTool || targetUid.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusyTool = true,
                    errorMessage = null,
                    statusMessage = "Đang nạp ${skdbFile.name} sang UID $targetUid..."
                )
            }
            advancedTools.importSkdbToActiveUid(state.scan, skdbFile, targetUid)
                .onSuccess { res ->
                    val history = engine.loadRollbackHistory()
                    val newScan = engine.scan(preferredUid = targetUid, packageOverride = state.scan.packageName)
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            scan = newScan,
                            rollbackHistory = history,
                            lastApplySucceeded = true,
                            progressLogs = it.progressLogs + res.logs,
                            statusMessage = "✓ Đã nạp ${res.copiedFilesCount} tệp từ ${skdbFile.name} sang UID $targetUid!"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isBusyTool = false,
                            statusMessage = null,
                            errorMessage = "Nạp .skdb sang UID thất bại: ${err.message}"
                        )
                    }
                }
        }
    }

    fun launchSoulKnight() {
        val pkg = _uiState.value.scan.packageName
        if (pkg.isBlank()) return
        viewModelScope.launch {
            val ok = engine.launchGame(pkg)
            if (!ok) {
                _uiState.update { it.copy(errorMessage = "Không thể mở tự động '$pkg' — hãy mở từ màn hình chính giả lập.") }
            }
        }
    }

    private suspend fun loadSkdbList(): List<File> = withContext(Dispatchers.IO) {
        val dir = storagePaths.getBackupsDir()
        dir.listFiles { f -> f.isFile && f.name.endsWith(RustCoreEngine.SKDB_EXTENSION, ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
