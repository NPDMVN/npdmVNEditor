package com.dmedit.core.shell

import android.content.Context
import android.content.Intent
import com.dmedit.core.model.GameProfile
import com.dmedit.core.nativebridge.RustCoreEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

data class HeroOption(
    val key: String,
    val displayName: String,
    val nameEn: String = key
) {
    fun localizedName(lang: AppLanguage): String = if (lang == AppLanguage.EN) nameEn else displayName
}

data class QuickModTargets(
    val gameFile: String? = null,
    val prefsFile: String? = null,
    val itemFile: String? = null,
    val weaponEvolutionFile: String? = null,
    val statisticFile: String? = null,
    val seasonFile: String? = null,
    val escapeSeasonFile: String? = null,
    val monsriseFile: String? = null,
    val miscFile: String? = null
)

data class QuickModScanResult(
    val hasRoot: Boolean = false,
    val rootMessage: String = "",
    val packageName: String = "",
    val filesDir: String = "",
    val availableUids: List<String> = emptyList(),
    val selectedUid: String = "",
    val preferModernFormat: Boolean = true,
    val targets: QuickModTargets = QuickModTargets(),
    val availableHeroes: List<HeroOption> = emptyList(),
    val defaultShadowFiles: List<String> = emptyList()
)

data class CloudSafetyReport(
    val highRiskWarnings: List<String> = emptyList(),
    val mediumWarnings: List<String> = emptyList(),
    val protections: List<String> = emptyList()
) {
    val hasHighRisk: Boolean get() = highRiskWarnings.isNotEmpty()
    val hasAnyWarning: Boolean get() = highRiskWarnings.isNotEmpty() || mediumWarnings.isNotEmpty()
}

data class QuickModPlan(
    // Nhóm 1: game.data + playerprefs.xml
    val unlockHeroes: Boolean = true,
    val maxHeroLevels: Boolean = true,
    val heroLevelTarget: Int = 7,
    val unlockSkins: Boolean = true,
    val skipRestrictedSkins: Boolean = false,
    val gemSkinsOnly: Boolean = false,
    val selectedHeroes: Set<String> = emptySet(),
    val unlockSkills: Boolean = true,
    val unlockPets: Boolean = true,
    val enableRebornCard: Boolean = true,
    val maxRoomObjects: Boolean = true,

    // Nhóm 2: Tiền tệ
    val editGems: Boolean = true,
    val gemsValue: Long = 500_000L,
    val editFishChips: Boolean = false,
    val fishChipsValue: Long = 50L,
    val editSeasonCoins: Boolean = true,
    val seasonCoinsValue: Long = 100_000L,
    val editEscapeMoney: Boolean = false,
    val escapeMoneyValue: Long = 500_000L,

    // Nhóm 3: item_data_<UID>_.data(.new)
    val fullMaterials: Boolean = true,
    val materialQty: Int = 500,
    val fullSeeds: Boolean = true,
    val seedQty: Int = 99,
    val fullBlueprints: Boolean = true,
    val fullTickets: Boolean = true,
    val ticketQty: Int = 99,
    val unlockGardenAndMoto: Boolean = true,
    val fullMythicWeapons: Boolean = true,
    val fullJewelry: Boolean = true,

    // Nhóm 4: Các shard chuyên biệt & Màn chơi / Chế độ
    val fullWeaponEvolutions: Boolean = true,
    val unlockAllForgeWeapons: Boolean = true,
    val fullSeasonChipsAndSkills: Boolean = true,
    val expandEscapeStorage: Boolean = false,
    val fullMonsterRise: Boolean = true,
    val fullKillEffects: Boolean = true,
    val unlockAllGameModes: Boolean = true,
    val unlockEscapeMapsAndBase: Boolean = true,

    // Tùy chọn an toàn & tự động hóa
    val autoCreateMissingModeFiles: Boolean = true,
    val autoCreateSkdbBackup: Boolean = true
) {
    val touchesGameData: Boolean
        get() = unlockHeroes || maxHeroLevels || unlockSkins || unlockSkills ||
            unlockPets || enableRebornCard || maxRoomObjects || editGems || editFishChips ||
            unlockAllGameModes

    val touchesItemData: Boolean
        get() = fullMaterials || fullSeeds || fullBlueprints || fullTickets ||
            unlockGardenAndMoto || fullMythicWeapons || fullJewelry || unlockAllGameModes

    val touchesStatisticData: Boolean
        get() = unlockAllForgeWeapons || unlockAllGameModes

    val touchesSeasonData: Boolean
        get() = editSeasonCoins || fullSeasonChipsAndSkills || unlockAllGameModes

    val touchesEscapeSeasonData: Boolean
        get() = editEscapeMoney || expandEscapeStorage || unlockEscapeMapsAndBase

    val touchesMonsriseData: Boolean
        get() = fullMonsterRise || unlockAllGameModes

    val selectedCount: Int
        get() = listOf(
            unlockHeroes, maxHeroLevels, unlockSkins, unlockSkills, unlockPets,
            enableRebornCard, maxRoomObjects, editGems, editFishChips,
            editSeasonCoins, editEscapeMoney, fullMaterials, fullSeeds,
            fullBlueprints, fullTickets, unlockGardenAndMoto, fullMythicWeapons,
            fullJewelry, fullWeaponEvolutions, unlockAllForgeWeapons,
            fullSeasonChipsAndSkills, expandEscapeStorage, fullMonsterRise,
            fullKillEffects, unlockAllGameModes, unlockEscapeMapsAndBase
        ).count { it }

    fun evaluateCloudSafety(lang: AppLanguage = AppLocaleHolder.current): CloudSafetyReport {
        val high = mutableListOf<String>()
        val med = mutableListOf<String>()

        if (editFishChips) {
            high += bi(
                "Cá khô (Fish Chips) là tiền nạp thật (IAP) được máy chủ đối chiếu biên lai — KHÔNG khuyến nghị sửa nếu định tải lên Cloud!",
                "Fish Chips are real-money IAP currency verified against server receipts — DO NOT edit if uploading to Cloud!",
                lang
            )
        }
        if (editGems && gemsValue > 2_000_000L) {
            med += bi(
                "Đá quý ($gemsValue) khá cao — khuyến nghị giữ ≤ 500.000–2.000.000 cho mỗi lần tải Cloud.",
                "Gems ($gemsValue) is quite high — recommended ≤ 500,000–2,000,000 per Cloud upload.",
                lang
            )
        }
        if (editSeasonCoins && seasonCoinsValue > 500_000L) {
            med += bi(
                "Xu Mùa giải ($seasonCoinsValue) khá cao — khuyến nghị giữ ≤ 100.000–500.000.",
                "Season Coins ($seasonCoinsValue) is quite high — recommended ≤ 100,000–500,000.",
                lang
            )
        }
        if (editEscapeMoney && escapeMoneyValue > 1_000_000L) {
            med += bi(
                "Tiền Sắt Đảo Điên ($escapeMoneyValue) khá cao — khuyến nghị giữ ≤ 1.000.000.",
                "Escape Iron Money ($escapeMoneyValue) is quite high — recommended ≤ 1,000,000.",
                lang
            )
        }
        if (fullMaterials && materialQty > 999) {
            med += bi(
                "Số lượng nguyên liệu (x$materialQty) cao hơn mức thông thường — khuyến nghị x200 hoặc x500.",
                "Material quantity (x$materialQty) is higher than usual — recommended x200 or x500.",
                lang
            )
        }
        if (unlockHeroes || (unlockSkins && !gemSkinsOnly)) {
            med += bi(
                "Lưu ý: ChillyRoom kiểm tra độc lập phía server (đặc biệt tướng/skin trả phí IAP). Để an toàn tối đa cho nick chính, hãy chọn preset 'No-IAP (Nick chính)' và luôn giữ sao lưu .skdb.",
                "Note: ChillyRoom runs server-side validation (especially for paid IAP heroes/skins). For main accounts, prefer the 'No-IAP (Main Acc)' preset and always keep a .skdb backup.",
                lang
            )
        }

        val protections = listOf(
            bi(
                "Tự khóa đúng tài khoản (UID) — không sửa nhầm dữ liệu mặc định",
                "Locked strictly to your Account UID — never edits default files",
                lang
            ),
            bi(
                "Giữ chỉ số hợp lệ chuẩn game (tướng tối đa Lv.7, trang sức bền 100)",
                "Keeps legitimate game limits (max Hero Lv.7, Jewelry durability 100)",
                lang
            ),
            bi(
                "Tự đồng bộ dữ liệu, kiểm tra lỗi file trước khi ghi & tạo bản hoàn tác",
                "Auto-syncs data, verifies file integrity before saving & keeps rollback backup",
                lang
            ),
            bi(
                "Tác giả (Nguyễn Phan Đức Mạnh) không chịu trách nhiệm nếu tài khoản bị ban",
                "Author (Nguyễn Phan Đức Mạnh) is not responsible for any account bans",
                lang
            )
        )

        return CloudSafetyReport(
            highRiskWarnings = high,
            mediumWarnings = med,
            protections = protections
        )
    }

    companion object {
        /**
         * Highest-safety profile for main accounts synced to Cloud:
         * avoids real-money IAP heroes/skills/paid skins/Fish Chips;
         * unlocks gem-purchasable skins (Value > 1), farmable resources, forge table & game modes.
         */
        fun noIapSafeDefaults(): QuickModPlan = QuickModPlan(
            unlockHeroes = false,
            maxHeroLevels = true,
            heroLevelTarget = 7,
            unlockSkins = true,
            skipRestrictedSkins = true,
            gemSkinsOnly = true,
            selectedHeroes = emptySet(),
            unlockSkills = false,
            unlockPets = false,
            enableRebornCard = false,
            maxRoomObjects = true,
            editGems = true,
            gemsValue = 300_000L,
            editFishChips = false,
            fishChipsValue = 50L,
            editSeasonCoins = true,
            seasonCoinsValue = 80_000L,
            editEscapeMoney = false,
            escapeMoneyValue = 300_000L,
            fullMaterials = true,
            materialQty = 200,
            fullSeeds = true,
            seedQty = 50,
            fullBlueprints = true,
            fullTickets = true,
            ticketQty = 50,
            unlockGardenAndMoto = true,
            fullMythicWeapons = true,
            fullJewelry = true,
            fullWeaponEvolutions = true,
            unlockAllForgeWeapons = true,
            fullSeasonChipsAndSkills = true,
            expandEscapeStorage = false,
            fullMonsterRise = true,
            fullKillEffects = false,
            unlockAllGameModes = true,
            unlockEscapeMapsAndBase = true,
            autoCreateMissingModeFiles = true,
            autoCreateSkdbBackup = true
        )

        fun cloudSafeDefaults(): QuickModPlan = QuickModPlan(
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
            gemsValue = 500_000L,
            editFishChips = false,
            fishChipsValue = 50L,
            editSeasonCoins = true,
            seasonCoinsValue = 100_000L,
            editEscapeMoney = false,
            escapeMoneyValue = 500_000L,
            fullMaterials = true,
            materialQty = 500,
            fullSeeds = true,
            seedQty = 99,
            fullBlueprints = true,
            fullTickets = true,
            ticketQty = 99,
            unlockGardenAndMoto = true,
            fullMythicWeapons = true,
            fullJewelry = true,
            fullWeaponEvolutions = true,
            unlockAllForgeWeapons = true,
            fullSeasonChipsAndSkills = true,
            expandEscapeStorage = false,
            fullMonsterRise = true,
            fullKillEffects = true,
            unlockAllGameModes = true,
            unlockEscapeMapsAndBase = true,
            autoCreateMissingModeFiles = true,
            autoCreateSkdbBackup = true
        )
    }
}

data class RollbackEntry(
    val targetPath: String,
    val snapshotPath: String,
    val createdNew: Boolean = false
)

data class QuickRollbackSession(
    val id: String,
    val timestampMs: Long,
    val packageName: String,
    val uid: String,
    val summary: String,
    val entries: List<RollbackEntry>
)

data class QuickModApplyResult(
    val updatedFilesCount: Int,
    val logs: List<String>,
    val rollbacks: List<RollbackEntry>,
    val savedSession: QuickRollbackSession? = null,
    val autoBackupPath: String? = null
)

@Singleton
class SoulKnightQuickPatchEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootProber: AdaptiveRootProber,
    private val rootBackend: AndroidRootBackend,
    private val profileRepository: ProfileRepository,
    private val rustCore: RustCoreEngine,
    private val formatSyncer: SoulKnightFormatSyncer,
    private val storagePaths: AppStoragePaths
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val uidSuffixRegex = Regex("^[a-z0-9_]+_([0-9]+|[0-9a-f]{16})_\\.data(?:\\.(?:new|rij))?$", RegexOption.IGNORE_CASE)
    private val defaultShadowShardRegex = Regex(
        "^(?:(?:item_data|season_data|escape_season_data|escape_run_save|statistic|weapon_evolution_data|misc_data|monsrise_data|bp_data|task|pvp_data|setting|mall_reload_data)\\.data(?:\\.(?:new|rij))?|[a-z0-9_]+_LOCAL\\.data(?:\\.(?:new|rij))?)$",
        RegexOption.IGNORE_CASE
    )

    suspend fun scan(
        preferredUid: String? = null,
        packageOverride: String? = null
    ): QuickModScanResult = withContext(Dispatchers.IO) {
        val rootInfo = rootProber.probe()
        if (!rootInfo.hasRoot) {
            return@withContext QuickModScanResult(
                hasRoot = false,
                rootMessage = rootInfo.diagnosticMessage
            )
        }

        val bundle = loadSoulKnightBundle()
            ?: return@withContext QuickModScanResult(
                hasRoot = true,
                rootMessage = "Không tìm thấy profile Soul Knight tích hợp"
            )
        val profile = bundle.profile

        val installed = rootBackend.listInstalledPackages()
        val pkg = when {
            !packageOverride.isNullOrBlank() -> packageOverride
            else -> profile.packages.firstOrNull { it in installed } ?: profile.packages.firstOrNull().orEmpty()
        }
        val filesDir = rootBackend.resolveGameFilesDir(rootInfo.activeUserId, pkg, profile)
        val entries = rootBackend.listDirectory(filesDir, profile).getOrDefault(emptyList())
        val fileNames = entries.filter { !it.stat.isDirectory }.map { it.name }.toSet()

        val prefsSummary = formatSyncer.inspectPrefsAccounts(profile, pkg)

        val uidSet = linkedSetOf<String>()
        prefsSummary.activeUidHint?.takeIf { it.isNotBlank() }?.let { uidSet += it }
        prefsSummary.observedUids.filter { it.isNotBlank() }.forEach { uidSet += it }
        fileNames.forEach { name ->
            uidSuffixRegex.matchEntire(name)?.groupValues?.getOrNull(1)?.let { uidSet += it }
        }
        // Strict guard: only offer Guest (no-UID) default files if NO logged-in account UID exists!
        if (uidSet.isEmpty()) {
            uidSet += ""
        }

        val availableUids = uidSet.toList()
        val chosenUid = when {
            preferredUid != null && preferredUid in availableUids -> preferredUid
            !prefsSummary.activeUidHint.isNullOrBlank() && prefsSummary.activeUidHint in availableUids ->
                prefsSummary.activeUidHint
            availableUids.any { it.isNotBlank() } -> availableUids.first { it.isNotBlank() }
            else -> ""
        }

        val hasModernForUid = fileNames.any { name ->
            val lower = name.lowercase()
            (lower.endsWith(".data.new") || lower.endsWith(".data.rij")) &&
                (chosenUid.isBlank() || lower.contains("_${chosenUid.lowercase()}_."))
        }
        val preferModern = prefsSummary.isModernRijEnabled || hasModernForUid

        val targets = resolveTargetsForUid(
            filesDir = filesDir,
            fileNames = fileNames,
            prefsPath = prefsSummary.prefsPath,
            uid = chosenUid,
            preferModern = preferModern
        )

        val defaultShadowFiles = if (chosenUid.isNotBlank()) {
            fileNames.filter { defaultShadowShardRegex.matches(it) }.sorted()
        } else {
            emptyList()
        }

        val catalog = profileRepository.loadItemCatalog(bundle, "catalogs/items_vi_en.json")
        val availableHeroes = loadAvailableHeroes(profile, targets.gameFile, catalog)

        QuickModScanResult(
            hasRoot = true,
            rootMessage = rootInfo.diagnosticMessage,
            packageName = pkg,
            filesDir = filesDir,
            availableUids = availableUids,
            selectedUid = chosenUid,
            preferModernFormat = preferModern,
            targets = targets,
            availableHeroes = availableHeroes,
            defaultShadowFiles = defaultShadowFiles
        )
    }

    suspend fun applyPlan(
        scan: QuickModScanResult,
        plan: QuickModPlan,
        onProgress: (String) -> Unit
    ): Result<QuickModApplyResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(scan.hasRoot) { "Chưa có quyền Root để áp dụng sửa nhanh" }
            check(plan.selectedCount > 0) { "Hãy chọn ít nhất 1 mục trước khi áp dụng" }

            val bundle = loadSoulKnightBundle() ?: error("Không tải được profile Soul Knight")
            val profile = bundle.profile
            val presets = profileRepository.loadPresetPack(bundle, "presets/full_resources_80800.json")
            check(presets.isNotEmpty()) { "Không tải được gói tài nguyên presets/full_resources_80800.json" }

            val logs = mutableListOf<String>()
            val rollbacks = mutableListOf<RollbackEntry>()
            val createdPrefsFlags = linkedMapOf<String, Int>()
            var updatedCount = 0
            var autoBackupPath: String? = null

            fun logStep(msg: String) {
                logs += msg
                onProgress(msg)
            }

            logStep("Đang dừng Soul Knight (${scan.packageName})...")
            rootBackend.forceStopPackage(scan.packageName)

            val t = scan.targets

            // Optional Step 0: Auto .skdb backup before writing any changes
            if (plan.autoCreateSkdbBackup) {
                logStep("Đang tự động sao lưu .skdb cho UID ${scan.selectedUid.ifBlank { "default" }}...")
                createQuickSkdbBackup(scan, profile)
                    .onSuccess { path ->
                        autoBackupPath = path
                        logStep("✓ Đã tạo bản sao lưu an toàn: ${File(path).name}")
                    }
                    .onFailure { err ->
                        logStep("⚠ Bỏ qua auto-backup .skdb (${err.message}) — vẫn tạo rollback từng tệp")
                    }
            }

            val heroFilter = plan.selectedHeroes
            val hasHeroFilter = heroFilter.isNotEmpty()
            val heroScopeLabel = if (hasHeroFilter) "${heroFilter.size} tướng đã chọn" else "42 tướng"

            // Step 1: game.data + automatic playerprefs.xml mirror sync
            if (plan.touchesGameData) {
                val gamePath = t.gameFile
                if (gamePath == null) {
                    logStep("⚠ Bỏ qua game.data (không tìm thấy tệp)")
                } else {
                    logStep("Đang xử lý game.data (kiểm tra tính toàn vẹn Cloud)...")
                    val raw = rootBackend.readBytes(gamePath).getOrThrow()
                    var doc = rustCore.decryptBytes(profile, "sk_xor", raw).toString(Charsets.UTF_8)
                    val parsedGame = runCatching { json.parseToJsonElement(doc) as? JsonObject }.getOrNull()
                    val notes = mutableListOf<String>()

                    if (plan.unlockHeroes) {
                        if (hasHeroFilter) {
                            val curHeroes = parsedGame?.get("heroUnlock") as? JsonObject
                            if (curHeroes != null) {
                                val patched = JsonObject(curHeroes.mapValues { (k, v) ->
                                    if (k in heroFilter) JsonPrimitive(true) else v
                                })
                                doc = rustCore.patchJsonPath(doc, "$.heroUnlock", patched)
                            }
                        } else {
                            doc = rustCore.batchSetMap(doc, "$.heroUnlock", JsonPrimitive(true))
                        }
                        notes += heroScopeLabel
                    }
                    if (plan.maxHeroLevels) {
                        val lvl = plan.heroLevelTarget.coerceIn(1, 7) // True Soul Knight max level = 7
                        val curLevels = parsedGame?.get("heroLevel") as? JsonObject
                        if (curLevels != null) {
                            val patchedLevels = JsonObject(curLevels.mapValues { (k, el) ->
                                val cur = (el as? JsonPrimitive)?.intOrNull ?: 0
                                if (!hasHeroFilter || k in heroFilter) {
                                    JsonPrimitive(maxOf(cur, lvl).coerceAtMost(7))
                                } else {
                                    el
                                }
                            })
                            doc = rustCore.patchJsonPath(doc, "$.heroLevel", patchedLevels)
                        } else if (!hasHeroFilter) {
                            doc = rustCore.batchSetMap(doc, "$.heroLevel", JsonPrimitive(lvl))
                        }
                        notes += "cấp $lvl chuẩn"
                    }
                    if (plan.unlockSkins) {
                        val skinLockObj = parsedGame?.get("skinLock") as? JsonObject
                        when {
                            plan.gemSkinsOnly && skinLockObj != null -> {
                                // No-IAP mode: ONLY unlock gem-purchasable skins (Value > 1), skip paid/negative & special (0)
                                var unlockedGemSkins = 0
                                val gemOnlyLock = JsonObject(skinLockObj.mapValues { (heroKey, arrEl) ->
                                    if (hasHeroFilter && heroKey !in heroFilter) return@mapValues arrEl
                                    val arr = arrEl as? JsonArray ?: return@mapValues arrEl
                                    JsonArray(arr.map { entryEl ->
                                        val obj = entryEl as? JsonObject ?: return@map entryEl
                                        val curVal = (obj["Value"] as? JsonPrimitive)?.intOrNull
                                        if (curVal != null && curVal > 1) {
                                            unlockedGemSkins++
                                            JsonObject(obj + ("Value" to JsonPrimitive(1)))
                                        } else {
                                            obj
                                        }
                                    })
                                })
                                doc = rustCore.patchJsonPath(doc, "$.skinLock", gemOnlyLock)
                                notes += "chỉ mở $unlockedGemSkins skin Gems (No-IAP)"
                            }
                            plan.skipRestrictedSkins && skinLockObj != null -> {
                                var skippedCount = 0
                                val safeSkinLock = JsonObject(skinLockObj.mapValues { (heroKey, arrEl) ->
                                    if (hasHeroFilter && heroKey !in heroFilter) return@mapValues arrEl
                                    val arr = arrEl as? JsonArray ?: return@mapValues arrEl
                                    JsonArray(arr.map { entryEl ->
                                        val obj = entryEl as? JsonObject ?: return@map entryEl
                                        val curVal = (obj["Value"] as? JsonPrimitive)?.intOrNull
                                        if (curVal == null || curVal == 0 || curVal <= -6) {
                                            skippedCount++
                                            obj
                                        } else {
                                            JsonObject(obj + ("Value" to JsonPrimitive(1)))
                                        }
                                    })
                                })
                                doc = rustCore.patchJsonPath(doc, "$.skinLock", safeSkinLock)
                                notes += "skin cơ bản (bỏ qua $skippedCount slot 0/≤-6)"
                            }
                            hasHeroFilter && skinLockObj != null -> {
                                val filteredSkinLock = JsonObject(skinLockObj.mapValues { (heroKey, arrEl) ->
                                    if (heroKey !in heroFilter) return@mapValues arrEl
                                    val arr = arrEl as? JsonArray ?: return@mapValues arrEl
                                    JsonArray(arr.map { entryEl ->
                                        val obj = entryEl as? JsonObject ?: return@map entryEl
                                        if ("Value" in obj) JsonObject(obj + ("Value" to JsonPrimitive(1))) else obj
                                    })
                                })
                                doc = rustCore.patchJsonPath(doc, "$.skinLock", filteredSkinLock)
                                notes += "full skin ($heroScopeLabel)"
                            }
                            else -> {
                                doc = rustCore.batchUpdateNestedPairs(doc, "$.skinLock", null, "Value", JsonPrimitive(1))
                                notes += "full 100% skin"
                            }
                        }
                    }
                    if (plan.unlockSkills) {
                        if (hasHeroFilter) {
                            val skillObj = parsedGame?.get("heroSkillUnlock") as? JsonObject
                            if (skillObj != null) {
                                val filteredSkills = JsonObject(skillObj.mapValues { (heroKey, arrEl) ->
                                    if (heroKey !in heroFilter) return@mapValues arrEl
                                    val arr = arrEl as? JsonArray ?: return@mapValues arrEl
                                    JsonArray(arr.map { entryEl ->
                                        val obj = entryEl as? JsonObject ?: return@map entryEl
                                        if ("Value" in obj) JsonObject(obj + ("Value" to JsonPrimitive(true))) else obj
                                    })
                                })
                                doc = rustCore.patchJsonPath(doc, "$.heroSkillUnlock", filteredSkills)
                            }
                            notes += "full kỹ năng ($heroScopeLabel)"
                        } else {
                            doc = rustCore.batchUpdateNestedPairs(doc, "$.heroSkillUnlock", null, "Value", JsonPrimitive(true))
                            notes += "full kỹ năng"
                        }
                    }
                    if (plan.unlockPets) {
                        doc = rustCore.batchSetMap(doc, "$.petUnlock", JsonPrimitive(true))
                        notes += "58 thú cưng"
                    }
                    if (plan.enableRebornCard) {
                        doc = rustCore.patchJsonPath(doc, "$.rebornCard", JsonPrimitive(true))
                        notes += "thẻ hồi sinh"
                    }
                    if (plan.maxRoomObjects) {
                        presets["room_objects_max"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.roomObjectLevel", p, overwriteExisting = true)
                            notes += "phòng khách Lv.5"
                        }
                    }
                    if (plan.editGems) {
                        val g = plan.gemsValue.coerceIn(0L, 2_000_000_000L)
                        val existingCum = (parsedGame?.get("cumulativeGems") as? JsonPrimitive)?.longOrNull ?: 0L
                        val safeCum = maxOf(existingCum, g)
                        doc = rustCore.patchJsonPath(doc, "$.gems", JsonPrimitive(g))
                        doc = rustCore.patchJsonPath(doc, "$.lastGems", JsonPrimitive(g))
                        doc = rustCore.patchJsonPath(doc, "$.varGems", JsonPrimitive(0))
                        doc = rustCore.patchJsonPath(doc, "$.cumulativeGems", JsonPrimitive(safeCum))
                        notes += "$g Gems (cum≥$safeCum)"
                    }
                    if (plan.editFishChips) {
                        val fc = plan.fishChipsValue.coerceIn(0L, 999_999L)
                        doc = rustCore.patchJsonPath(doc, "$.fishChip", JsonPrimitive(fc))
                        notes += "$fc Cá khô"
                    }
                    if (plan.unlockAllGameModes) {
                        val curTut = (parsedGame?.get("hasTutorial") as? JsonPrimitive)?.intOrNull ?: 0
                        val curFirstRoom = (parsedGame?.get("firstInRoom") as? JsonPrimitive)?.intOrNull ?: 0
                        val curTotalIdx = (parsedGame?.get("totalIndex") as? JsonPrimitive)?.intOrNull ?: 0
                        doc = rustCore.patchJsonPath(doc, "$.hasTutorial", JsonPrimitive(maxOf(curTut, 1)))
                        doc = rustCore.patchJsonPath(doc, "$.firstInRoom", JsonPrimitive(maxOf(curFirstRoom, 20)))
                        doc = rustCore.patchJsonPath(doc, "$.totalIndex", JsonPrimitive(maxOf(curTotalIdx, 15)))
                        if (!plan.maxRoomObjects) {
                            presets["room_objects_max"]?.let { p ->
                                doc = rustCore.mergeJsonPreset(doc, "$.roomObjectLevel", p, overwriteExisting = true)
                            }
                        }
                        val curCerts = parsedGame?.get("normalModePassCertificates") as? JsonArray
                        if (curCerts == null || curCerts.isEmpty()) {
                            val certElem = json.parseToJsonElement(
                                """[{"isBadass":true,"isExtendedLevel":true,"battleFactors":[],"characterBuffs":[],"buffs":[7,9,16,21,38],"additionBuffs":[],"drinks":[],"weaponNames":["weapon_001"],"weaponItems":[""],"weaponAffixes":[],"weaponSkins":[0],"weaponEvolutionStatus":[],"costTime":"00:24:15","magicStoneIdx":0,"version":"","difficulty":"Tier3","weaponAffixCertificateData":[],"gameMode":"Normal","cellarPassLevel":2,"characterIdx":0,"characterSkinIdx":0,"petIndex":0,"followerId":"","skillIndex":0,"date":"26-11-22","killCount":486,"gainCoin":640,"dealDamage":32450,"get":false}]"""
                            )
                            doc = rustCore.patchJsonPath(doc, "$.normalModePassCertificates", certElem)
                        }
                        notes += "mở khóa tiến độ Sảnh & Chế độ chơi"
                    }

                    val minified = validateAndMinifyJson(doc, "game.data")
                    val encoded = encryptAndVerifyRoundTrip(profile, "sk_xor", minified, raw, "game.data")
                    val snap = rootBackend.writeBytesTransactional(gamePath, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(gamePath, snap.absolutePath)
                    updatedCount++
                    logStep("✓ game.data: ${notes.joinToString(", ")}")

                    logStep("Đang đồng bộ sang playerprefs.xml (UID: ${scan.selectedUid.ifBlank { "default" }})...")
                    val syncRes = formatSyncer.syncGameDataToPlayerPrefs(
                        gameJsonText = minified,
                        profile = profile,
                        packageName = scan.packageName,
                        currentFilePath = gamePath,
                        forcedAccountUid = scan.selectedUid
                    ).getOrThrow()
                    syncRes.rollbackPath?.let { rbPath ->
                        rollbacks += RollbackEntry(syncRes.prefsPath, rbPath)
                    }
                    updatedCount++
                    logStep("✓ playerprefs.xml: đồng bộ ${syncRes.updatedKeysCount} khóa cho UID ${syncRes.syncedAccounts.joinToString(", ")}")
                }
            }

            // Step 2: item_data_<UID>_.data(.new)
            if (plan.touchesItemData) {
                val itemPath = t.itemFile
                if (itemPath == null) {
                    logStep("⚠ Bỏ qua item_data (chưa tìm thấy tệp cho UID này)")
                } else {
                    val fileName = File(itemPath).name
                    val isModern = isModernPath(itemPath)
                    val codec = if (isModern) "sk_aes_v2" else "sk_des_default"
                    logStep("Đang xử lý $fileName...")
                    val raw = rootBackend.readBytes(itemPath).getOrThrow()
                    var doc = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                    val parsedItem = runCatching { json.parseToJsonElement(doc) as? JsonObject }.getOrNull()
                    val notes = mutableListOf<String>()

                    if (plan.fullMaterials) {
                        val baseMat = presets["materials_all"] as? JsonObject
                        val curMat = parsedItem?.get("materials") as? JsonObject
                        if (baseMat != null) {
                            val qty = plan.materialQty.coerceIn(1, 999_999)
                            val scaled = JsonObject(baseMat.mapValues { (k, _) ->
                                val cur = (curMat?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
                                val target = when {
                                    k.startsWith("material_tape_") || k.startsWith("material_zongzi_leaf_") -> 1
                                    k.startsWith("material_c") ||
                                        k.startsWith("material_skill_") ||
                                        k.startsWith("material_skin_fragment_") -> 20
                                    k == "material_jewelry_fragment" -> minOf(qty, 99)
                                    else -> qty
                                }
                                JsonPrimitive(maxOf(cur, target))
                            })
                            doc = rustCore.mergeJsonPreset(doc, "$.materials", scaled, overwriteExisting = true)
                            notes += "133 nguyên liệu & mảnh (x$qty)"
                        }
                    }
                    if (plan.fullSeeds) {
                        val baseSeeds = presets["seeds_all"] as? JsonObject
                        val curSeeds = parsedItem?.get("seeds") as? JsonObject
                        if (baseSeeds != null) {
                            val qty = plan.seedQty.coerceIn(1, 99_999)
                            val scaled = JsonObject(baseSeeds.mapValues { (k, _) ->
                                val cur = (curSeeds?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
                                JsonPrimitive(maxOf(cur, qty))
                            })
                            doc = rustCore.mergeJsonPreset(doc, "$.seeds", scaled, overwriteExisting = true)
                            notes += "47 hạt giống (x$qty)"
                        }
                    }
                    if (plan.fullBlueprints) {
                        val bpKey = if (isModern) "blueprints_modern" else "blueprints_legacy"
                        presets[bpKey]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.blueprints", p, overwriteExisting = true)
                            notes += "280 bản vẽ chuẩn 8.8"
                        }
                    }
                    if (plan.fullTickets) {
                        val baseTickets = presets["token_tickets_all"] as? JsonObject
                        val curTickets = parsedItem?.get("tokenTickets") as? JsonObject
                        if (baseTickets != null) {
                            val qty = plan.ticketQty.coerceIn(1, 99_999)
                            val scaled = JsonObject(baseTickets.mapValues { (k, _) ->
                                val cur = (curTickets?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
                                JsonPrimitive(maxOf(cur, qty))
                            })
                            doc = rustCore.mergeJsonPreset(doc, "$.tokenTickets", scaled, overwriteExisting = true)
                        }
                        presets["special_tickets_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.specialTickets", p, overwriteExisting = false)
                        }
                        notes += "229 phiếu (x${plan.ticketQty})"
                    }
                    if (plan.unlockGardenAndMoto) {
                        presets["item_unlock_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.itemUnlock", p, overwriteExisting = false)
                        }
                        presets["hand_styles_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.handStyleIndexList", p, overwriteExisting = false)
                        }
                        notes += "full ô vườn, xe máy & đòn tay không"
                    }
                    if (plan.fullMythicWeapons) {
                        presets["mythic_weapons_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(
                                doc,
                                "$.mythicWeapons",
                                p,
                                overwriteExisting = true,
                                dedupeKey = "id"
                            )
                            notes += "32 vũ khí Thần thoại Lv.3"
                        }
                    }
                    if (plan.fullJewelry) {
                        val jKey = if (isModern) "jewelry_modern" else "jewelry_legacy"
                        presets[jKey]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.jewelryData", p, overwriteExisting = true)
                            notes += "15 trang sức (độ bền chuẩn 100)"
                        }
                    }
                    if (plan.unlockAllGameModes) {
                        doc = rustCore.patchJsonPath(doc, "$.got_bossrush_ticket", JsonPrimitive(true))
                        if (!plan.fullTickets) {
                            presets["special_tickets_all"]?.let { p ->
                                doc = rustCore.mergeJsonPreset(doc, "$.specialTickets", p, overwriteExisting = false)
                            }
                        }
                        if (!plan.fullMaterials) {
                            val curMat = parsedItem?.get("materials") as? JsonObject
                            val curFeather = (curMat?.get("material_ticket_bossrush") as? JsonPrimitive)?.intOrNull ?: 0
                            if (curFeather < 10) {
                                doc = rustCore.mergeJsonPreset(
                                    doc,
                                    "$.materials",
                                    JsonObject(mapOf("material_ticket_bossrush" to JsonPrimitive(10))),
                                    overwriteExisting = true
                                )
                            }
                        }
                        notes += "vé Boss Rush & Hopper"
                    }

                    val minified = validateAndMinifyJson(doc, fileName)
                    val encoded = encryptAndVerifyRoundTrip(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(itemPath, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(itemPath, snap.absolutePath)
                    updatedCount++
                    logStep("✓ $fileName: ${notes.joinToString(", ")}")
                }
            }

            // Step 3: weapon_evolution_data_<UID>_.data(.new)
            if (plan.fullWeaponEvolutions) {
                val wePath = t.weaponEvolutionFile
                if (wePath == null) {
                    logStep("⚠ Bỏ qua weapon_evolution_data (chưa có tệp)")
                } else {
                    val fileName = File(wePath).name
                    val codec = if (isModernPath(wePath)) "sk_aes_v2" else "sk_des_default"
                    logStep("Đang xử lý $fileName...")
                    val raw = rootBackend.readBytes(wePath).getOrThrow()
                    var doc = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                    presets["weapon_evolutions_all"]?.let { p ->
                        doc = rustCore.mergeJsonPreset(doc, "$.weapons", p, overwriteExisting = true)
                    }
                    val minified = validateAndMinifyJson(doc, fileName)
                    val encoded = encryptAndVerifyRoundTrip(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(wePath, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(wePath, snap.absolutePath)
                    updatedCount++
                    logStep("✓ $fileName: nạp full 175 vũ khí tiến hóa & skin vũ khí")
                }
            }

            // Step 4: statistic_<UID>_.data(.new) -> Forge Table + Game Mode / Lobby / Tier Unlocks
            if (plan.touchesStatisticData) {
                val statPath = t.statisticFile
                if (statPath == null) {
                    logStep("⚠ Bỏ qua statistic (chưa có tệp)")
                } else {
                    val fileName = File(statPath).name
                    val codec = if (isModernPath(statPath)) "sk_aes_v2" else "sk_des_statistic"
                    logStep("Đang xử lý $fileName...")
                    val raw = rootBackend.readBytes(statPath).getOrThrow()
                    var doc = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                    val parsedRoot = runCatching { json.parseToJsonElement(doc) as? JsonObject }.getOrNull()
                    val statData = parsedRoot?.get("StatisticsData") as? JsonObject
                    val notes = mutableListOf<String>()

                    if (plan.unlockAllForgeWeapons) {
                        val forgePreset = presets["forge_weapons_all"] as? JsonObject
                        if (forgePreset != null) {
                            val weaponStat = statData?.get("WeaponGameStatisticData") as? JsonObject
                            val existingUsed = weaponStat?.get("_weaponUsedTimes") as? JsonObject
                            val existingObtain = parsedRoot?.get("object2ObtainTime") as? JsonObject

                            val smartUsed = JsonObject(forgePreset.mapValues { (wKey, targetEl) ->
                                val cur = (existingUsed?.get(wKey) as? JsonPrimitive)?.intOrNull ?: 0
                                val want = (targetEl as? JsonPrimitive)?.intOrNull ?: 8
                                JsonPrimitive(maxOf(cur, want))
                            })
                            val smartObtain = JsonObject(forgePreset.mapValues { (wKey, targetEl) ->
                                val cur = (existingObtain?.get(wKey) as? JsonPrimitive)?.intOrNull ?: 0
                                val want = (targetEl as? JsonPrimitive)?.intOrNull ?: 8
                                JsonPrimitive(maxOf(cur, want))
                            })

                            // 1. Soul Knight Forge Table & Cellar Weapon Codex read $.object2ObtainTime!
                            doc = rustCore.mergeJsonPreset(
                                doc,
                                "$.object2ObtainTime",
                                smartObtain,
                                overwriteExisting = true
                            )

                            // 2. Also keep StatisticsData.WeaponGameStatisticData._weaponUsedTimes in sync
                            if (weaponStat != null) {
                                doc = rustCore.mergeJsonPreset(
                                    doc,
                                    "$.StatisticsData.WeaponGameStatisticData._weaponUsedTimes",
                                    smartUsed,
                                    overwriteExisting = true
                                )
                            } else {
                                val newWeaponStat = JsonObject(
                                    mapOf(
                                        "\$type" to JsonPrimitive("GameStatisticSystem.WeaponGameStatisticData"),
                                        "_weaponUsedTimes" to smartUsed
                                    )
                                )
                                doc = rustCore.patchJsonPath(
                                    doc,
                                    "$.StatisticsData.WeaponGameStatisticData",
                                    newWeaponStat
                                )
                            }
                            notes += "${forgePreset.size} vũ khí Bàn Rèn & Sổ tay (object2ObtainTime + _weaponUsedTimes ≥ 8)"
                        }
                    }

                    if (plan.unlockAllGameModes) {
                        val levelStat = statData?.get("LevelGameStatisticData") as? JsonObject

                        fun smartMaxIntMap(presetKey: String, existingObj: JsonObject?): JsonObject? {
                            val presetObj = presets[presetKey] as? JsonObject ?: return null
                            return JsonObject(presetObj.mapValues { (k, v) ->
                                val cur = (existingObj?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
                                val want = (v as? JsonPrimitive)?.intOrNull ?: 1
                                JsonPrimitive(maxOf(cur, want))
                            })
                        }

                        val enterMerged = smartMaxIntMap(
                            "mode_unlock_enter_times",
                            levelStat?.get("_enterDungeonTimes") as? JsonObject
                        )
                        val finishMerged = smartMaxIntMap(
                            "mode_unlock_finish_times",
                            levelStat?.get("_finishDungeonTimes") as? JsonObject
                        )
                        val highestMerged = smartMaxIntMap(
                            "mode_unlock_highest_level",
                            levelStat?.get("_highestLevelArrived") as? JsonObject
                        )
                        val curBadass = (levelStat?.get("_enterBadAssTimes") as? JsonPrimitive)?.intOrNull ?: 0

                        if (levelStat != null) {
                            enterMerged?.let {
                                doc = rustCore.mergeJsonPreset(doc, "$.StatisticsData.LevelGameStatisticData._enterDungeonTimes", it, overwriteExisting = true)
                            }
                            finishMerged?.let {
                                doc = rustCore.mergeJsonPreset(doc, "$.StatisticsData.LevelGameStatisticData._finishDungeonTimes", it, overwriteExisting = true)
                            }
                            highestMerged?.let {
                                doc = rustCore.mergeJsonPreset(doc, "$.StatisticsData.LevelGameStatisticData._highestLevelArrived", it, overwriteExisting = true)
                            }
                            doc = rustCore.patchJsonPath(
                                doc,
                                "$.StatisticsData.LevelGameStatisticData._enterBadAssTimes",
                                JsonPrimitive(maxOf(curBadass, 1))
                            )
                        } else {
                            val newLevelStat = JsonObject(
                                buildMap {
                                    put("\$type", JsonPrimitive("GameStatisticSystem.LevelGameStatisticData"))
                                    put("_enterDungeonTimes", enterMerged ?: JsonObject(emptyMap()))
                                    put("_finishDungeonTimes", finishMerged ?: JsonObject(emptyMap()))
                                    put("_highestLevelArrived", highestMerged ?: JsonObject(emptyMap()))
                                    put("_enterBadAssTimes", JsonPrimitive(1))
                                }
                            )
                            doc = rustCore.patchJsonPath(doc, "$.StatisticsData.LevelGameStatisticData", newLevelStat)
                        }

                        presets["mode_unlock_recorded_events"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.recordedEvents", p, overwriteExisting = false)
                        }
                        presets["mode_unlock_pass_info"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.passInfo", p, overwriteExisting = false)
                        }
                        notes += "mở khóa tất cả chế độ chơi (Badass, Boss Rush, Cội Nguồn, Ma Trận, Tier 1-3 & 13 phòng sảnh)"
                    }

                    val minified = validateAndMinifyJson(doc, fileName)
                    val encoded = encryptAndVerifyRoundTrip(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(statPath, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(statPath, snap.absolutePath)
                    updatedCount++
                    logStep("✓ $fileName: ${notes.joinToString(", ")}")
                }
            }

            // Step 5: season_data_<UID>_.data(.new)
            if (plan.touchesSeasonData) {
                val seasonPath = t.seasonFile
                if (seasonPath == null) {
                    logStep("⚠ Bỏ qua season_data (chưa có tệp)")
                } else {
                    val fileName = File(seasonPath).name
                    val isModern = isModernPath(seasonPath)
                    val codec = if (isModern) "sk_aes_v2" else "sk_des_default"
                    logStep("Đang xử lý $fileName...")
                    val raw = rootBackend.readBytes(seasonPath).getOrThrow()
                    var doc = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                    val parsedSeason = runCatching { json.parseToJsonElement(doc) as? JsonObject }.getOrNull()
                    val notes = mutableListOf<String>()

                    if (plan.editSeasonCoins) {
                        val c = plan.seasonCoinsValue.coerceIn(0L, 99_999_999L)
                        val existingCum = (parsedSeason?.get("cumulativeCoinCount") as? JsonPrimitive)?.longOrNull ?: 0L
                        doc = rustCore.patchJsonPath(doc, "$.coin", JsonPrimitive(c))
                        doc = rustCore.patchJsonPath(doc, "$.cumulativeCoinCount", JsonPrimitive(maxOf(existingCum, c)))
                        notes += "$c Xu mùa giải"
                    }
                    if (plan.fullSeasonChipsAndSkills) {
                        val chipKey = if (isModern) "season_chips_modern" else "season_chips_legacy"
                        presets[chipKey]?.let { p ->
                            doc = rustCore.mergeJsonPreset(
                                doc,
                                "$.chips",
                                p,
                                overwriteExisting = true,
                                dedupeKey = "chipName"
                            )
                        }
                        for (skillField in listOf(
                            "$.hasUnlockTroop2SkillHaste",
                            "$.hasUnlockTroop2SkillFrostBurst",
                            "$.hasUnlockTroop2SkillShield",
                            "$.hasUnlockTroop2SkillPowerUp",
                            "$.hasUnlockTroop2SkillBonus"
                        )) {
                            doc = rustCore.patchJsonPath(doc, skillField, JsonPrimitive(true))
                        }
                        notes += "20 Chip Lv.10 + kỹ năng mùa giải"
                    }
                    if (plan.unlockAllGameModes) {
                        val curMecha = (parsedSeason?.get("mechaUnlockProgress") as? JsonPrimitive)?.intOrNull ?: 0
                        val curTroopDiff = (parsedSeason?.get("troop2UnlockedDifficulty") as? JsonPrimitive)?.intOrNull ?: 0
                        doc = rustCore.patchJsonPath(doc, "$.mechaUnlockProgress", JsonPrimitive(maxOf(curMecha, 1)))
                        doc = rustCore.patchJsonPath(doc, "$.troop2UnlockedDifficulty", JsonPrimitive(maxOf(curTroopDiff, 3)))
                        notes += "mở tiến độ & độ khó Mùa giải"
                    }

                    val minified = validateAndMinifyJson(doc, fileName)
                    val encoded = encryptAndVerifyRoundTrip(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(seasonPath, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(seasonPath, snap.absolutePath)
                    updatedCount++
                    logStep("✓ $fileName: ${notes.joinToString(", ")}")
                }
            }

            // Step 6: escape_season_data_<UID>_.data(.new)
            if (plan.touchesEscapeSeasonData) {
                val existingEscPath = t.escapeSeasonFile
                val shouldAutoCreate = existingEscPath == null && plan.autoCreateMissingModeFiles && scan.selectedUid.isNotBlank()
                val escPath = existingEscPath ?: if (shouldAutoCreate) {
                    val ext = if (scan.preferModernFormat) ".data.new" else ".data"
                    "${scan.filesDir}/escape_season_data_${scan.selectedUid}_$ext"
                } else null

                if (escPath == null) {
                    logStep("⚠ Bỏ qua escape_season_data (chưa có tệp — bật 'Tự tạo file chế độ' hoặc vào Đảo Điên 1 lần)")
                } else {
                    val fileName = File(escPath).name
                    val codec = if (isModernPath(escPath)) "sk_aes_v2" else "sk_des_default"
                    logStep(if (shouldAutoCreate) "Đang tự khởi tạo $fileName mới chuẩn 8.8..." else "Đang xử lý $fileName...")
                    val raw = if (shouldAutoCreate) ByteArray(0) else rootBackend.readBytes(escPath).getOrThrow()
                    var doc = if (shouldAutoCreate) {
                        MINIMAL_ESCAPE_TEMPLATE
                    } else {
                        rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                    }
                    val parsedEsc = runCatching { json.parseToJsonElement(doc) as? JsonObject }.getOrNull()
                    val notes = mutableListOf<String>()
                    if (shouldAutoCreate) notes += "tạo mới chuẩn 8.8"

                    if (plan.editEscapeMoney) {
                        val m = plan.escapeMoneyValue.coerceIn(0L, 99_999_999L)
                        val existingEarned = (parsedEsc?.get("totalIronMoneyEarned") as? JsonPrimitive)?.longOrNull ?: 0L
                        doc = rustCore.patchJsonPath(doc, "$.money", JsonPrimitive(m))
                        doc = rustCore.patchJsonPath(doc, "$.totalIronMoneyEarned", JsonPrimitive(maxOf(existingEarned, m)))
                        notes += "$m Tiền sắt"
                    }
                    if (plan.expandEscapeStorage) {
                        // Match real in-game upgraded limits (70 bag / 320 warehouse / 2 safe)
                        doc = rustCore.patchJsonPath(doc, "$.playerBag.slotCount", JsonPrimitive(70))
                        doc = rustCore.patchJsonPath(doc, "$.warehouse.slotCount", JsonPrimitive(320))
                        doc = rustCore.patchJsonPath(doc, "$.safeSlots.slotCount", JsonPrimitive(2))
                        notes += "túi 70 / kho 320 / két 2 (chuẩn game)"
                    }
                    if (plan.unlockEscapeMapsAndBase) {
                        doc = rustCore.patchJsonPath(doc, "$.hasEscapedTutorial", JsonPrimitive(true))
                        presets["escape_scenes_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.visitedEscapeSceneNames", p, overwriteExisting = false)
                        }
                        presets["escape_beacons_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.activatedBeacons", p, overwriteExisting = false)
                        }
                        presets["escape_heroes_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.unlockedHeroes", p, overwriteExisting = false)
                            doc = rustCore.mergeJsonPreset(doc, "$.rescuedHeroes", p, overwriteExisting = false)
                        }
                        presets["escape_npcs_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.rescuedNpcIds", p, overwriteExisting = false)
                        }
                        val presetBuildings = presets["escape_buildings_max"] as? JsonArray
                        if (presetBuildings != null) {
                            val curBuildings = (parsedEsc?.get("buildings") as? JsonArray)
                                ?.mapNotNull { it as? JsonObject }
                                ?.associateBy { (it["buildingName"] as? JsonPrimitive)?.content.orEmpty() }
                                .orEmpty()
                            val smartBuildings = JsonArray(presetBuildings.map { el ->
                                val obj = el as? JsonObject ?: return@map el
                                val bName = (obj["buildingName"] as? JsonPrimitive)?.content.orEmpty()
                                val wantLvl = (obj["level"] as? JsonPrimitive)?.intOrNull ?: 1
                                val curLvl = (curBuildings[bName]?.get("level") as? JsonPrimitive)?.intOrNull ?: 0
                                JsonObject(obj + ("level" to JsonPrimitive(maxOf(curLvl, wantLvl))) + ("initialized" to JsonPrimitive(true)))
                            })
                            doc = rustCore.mergeJsonPreset(doc, "$.buildings", smartBuildings, overwriteExisting = true, dedupeKey = "buildingName")
                        }
                        presets["escape_upgrade_blueprints_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.upgradeBlueprints", p, overwriteExisting = true, dedupeKey = "id")
                        }
                        notes += "5 bản đồ, 3 trụ, 15 tướng, 7 công trình & 22 nâng cấp Đảo Điên"
                    }

                    val minified = validateAndMinifyJson(doc, fileName)
                    val encoded = encryptAndVerifyRoundTrip(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(escPath, encoded, null, createRollback = !shouldAutoCreate).getOrThrow()
                    if (shouldAutoCreate) {
                        rollbacks += RollbackEntry(escPath, "", createdNew = true)
                        createdPrefsFlags["${scan.selectedUid}_has_escape_season_data"] = 1
                    } else if (snap != null) {
                        rollbacks += RollbackEntry(escPath, snap.absolutePath)
                    }
                    updatedCount++
                    logStep("✓ $fileName: ${notes.joinToString(", ")}")
                }
            }

            // Step 7: monsrise_data_<UID>_.data(.new)
            if (plan.touchesMonsriseData) {
                val existingMonsPath = t.monsriseFile
                val shouldAutoCreate = existingMonsPath == null && plan.autoCreateMissingModeFiles && scan.selectedUid.isNotBlank()
                val monsPath = existingMonsPath ?: if (shouldAutoCreate) {
                    val ext = if (scan.preferModernFormat) ".data.new" else ".data"
                    "${scan.filesDir}/monsrise_data_${scan.selectedUid}_$ext"
                } else null

                if (monsPath == null) {
                    logStep("⚠ Bỏ qua monsrise_data (chưa có tệp — bật 'Tự tạo file chế độ' hoặc vào Đấu trường Quái vật 1 lần)")
                } else {
                    val fileName = File(monsPath).name
                    val codec = if (isModernPath(monsPath)) "sk_aes_v2" else "sk_des_default"
                    logStep(if (shouldAutoCreate) "Đang tự khởi tạo $fileName mới chuẩn 8.8..." else "Đang xử lý $fileName...")
                    val raw = if (shouldAutoCreate) ByteArray(0) else rootBackend.readBytes(monsPath).getOrThrow()
                    var doc = if (shouldAutoCreate) {
                        MINIMAL_MONSRISE_TEMPLATE
                    } else {
                        rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                    }
                    val parsedMons = runCatching { json.parseToJsonElement(doc) as? JsonObject }.getOrNull()
                    val notes = mutableListOf<String>()
                    if (shouldAutoCreate) notes += "tạo mới chuẩn 8.8"

                    if (plan.fullMonsterRise) {
                        presets["monsrise_monsters_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.CollectedMonsters", p, overwriteExisting = true)
                        }
                        presets["monsrise_emblems_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.CollectedEmblems", p, overwriteExisting = true)
                        }
                        notes += "25 Quái vật Lv.5 & 35 Ấn ký Lv.8"
                    }
                    if (plan.fullMonsterRise || plan.unlockAllGameModes) {
                        presets["monsrise_unlocked_contents_all"]?.let { p ->
                            doc = rustCore.mergeJsonPreset(doc, "$.unlockedContents", p, overwriteExisting = false)
                        }
                        val curPve = (parsedMons?.get("pveGameTimes") as? JsonPrimitive)?.intOrNull ?: 0
                        doc = rustCore.patchJsonPath(doc, "$.pveGameTimes", JsonPrimitive(maxOf(curPve, 1)))
                        notes += "28 màn/nội dung Đấu trường Quái vật"
                    }

                    val minified = validateAndMinifyJson(doc, fileName)
                    val encoded = encryptAndVerifyRoundTrip(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(monsPath, encoded, null, createRollback = !shouldAutoCreate).getOrThrow()
                    if (shouldAutoCreate) {
                        rollbacks += RollbackEntry(monsPath, "", createdNew = true)
                        createdPrefsFlags["${scan.selectedUid}_has_monsrise_data"] = 1
                    } else if (snap != null) {
                        rollbacks += RollbackEntry(monsPath, snap.absolutePath)
                    }
                    updatedCount++
                    logStep("✓ $fileName: ${notes.joinToString(", ")}")
                }
            }

            // Step 8: misc_data_<UID>_.data(.new) -> 11 Kill Effects
            if (plan.fullKillEffects) {
                val existingMiscPath = t.miscFile
                val shouldAutoCreate = existingMiscPath == null && plan.autoCreateMissingModeFiles && scan.selectedUid.isNotBlank()
                val miscPath = existingMiscPath ?: if (shouldAutoCreate) {
                    val ext = if (scan.preferModernFormat) ".data.new" else ".data"
                    "${scan.filesDir}/misc_data_${scan.selectedUid}_$ext"
                } else null

                if (miscPath == null) {
                    logStep("⚠ Bỏ qua misc_data (chưa có tệp)")
                } else {
                    val fileName = File(miscPath).name
                    val codec = if (isModernPath(miscPath)) "sk_aes_v2" else "sk_des_default"
                    logStep(if (shouldAutoCreate) "Đang tự khởi tạo $fileName mới..." else "Đang xử lý $fileName...")
                    val raw = if (shouldAutoCreate) ByteArray(0) else rootBackend.readBytes(miscPath).getOrThrow()
                    var doc = if (shouldAutoCreate) {
                        MINIMAL_MISC_TEMPLATE
                    } else {
                        rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                    }
                    presets["kill_effects_all"]?.let { p ->
                        doc = rustCore.mergeJsonPreset(doc, "$.unlockedKillEffectIds", p, overwriteExisting = false)
                    }
                    val minified = validateAndMinifyJson(doc, fileName)
                    val encoded = encryptAndVerifyRoundTrip(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(miscPath, encoded, null, createRollback = !shouldAutoCreate).getOrThrow()
                    if (shouldAutoCreate) {
                        rollbacks += RollbackEntry(miscPath, "", createdNew = true)
                        createdPrefsFlags["${scan.selectedUid}_has_misc_data"] = 1
                    } else if (snap != null) {
                        rollbacks += RollbackEntry(miscPath, snap.absolutePath)
                    }
                    updatedCount++
                    logStep("✓ $fileName: mở full 11 hiệu ứng kết liễu")
                }
            }

            // Step 9: Enable playerprefs flags for any auto-created mode files
            if (createdPrefsFlags.isNotEmpty()) {
                formatSyncer.setPrefsIntFlags(profile, scan.packageName, createdPrefsFlags)
                    .onSuccess { res ->
                        if (res.rollbackPath != null && rollbacks.none { it.targetPath == res.prefsPath }) {
                            rollbacks += RollbackEntry(res.prefsPath, res.rollbackPath)
                        }
                        if (res.updatedKeysCount > 0) {
                            logStep("✓ playerprefs.xml: đã bật ${res.updatedKeysCount} cờ nhận diện tệp chế độ mới")
                        }
                    }
            }

            val savedSession = if (rollbacks.isNotEmpty()) {
                saveRollbackSession(
                    packageName = scan.packageName,
                    uid = scan.selectedUid,
                    summary = "Sửa nhanh ${plan.selectedCount} mục ($updatedCount tệp)",
                    entries = rollbacks
                )
            } else null

            logStep("🎉 Hoàn tất! Đã cập nhật, kiểm chứng giải mã ngược & xác thực byte cho $updatedCount tệp.")
            QuickModApplyResult(
                updatedFilesCount = updatedCount,
                logs = logs,
                rollbacks = rollbacks,
                savedSession = savedSession,
                autoBackupPath = autoBackupPath
            )
        }
    }

    suspend fun rollbackBatch(
        packageName: String,
        rollbacks: List<RollbackEntry>
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (packageName.isNotBlank()) rootBackend.forceStopPackage(packageName)
            var restored = 0
            for (rb in rollbacks) {
                if (rb.createdNew) {
                    if (rootBackend.exists(rb.targetPath)) {
                        rootBackend.deleteFile(rb.targetPath).getOrThrow()
                        restored++
                    }
                } else {
                    val snapFile = File(rb.snapshotPath)
                    if (snapFile.exists()) {
                        rootBackend.writeBytesTransactional(
                            path = rb.targetPath,
                            bytes = snapFile.readBytes(),
                            packageNameToStop = null,
                            createRollback = false
                        ).getOrThrow()
                        restored++
                    }
                }
            }
            restored
        }
    }

    suspend fun loadRollbackHistory(): List<QuickRollbackSession> = withContext(Dispatchers.IO) {
        val histFile = File(storagePaths.getQuickRollbackDir(), "history.json")
        if (!histFile.exists()) return@withContext emptyList()
        runCatching {
            val arr = json.parseToJsonElement(histFile.readText(Charsets.UTF_8)) as? JsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val entries = (obj["entries"] as? JsonArray)?.mapNotNull { eEl ->
                    val eo = eEl as? JsonObject ?: return@mapNotNull null
                    val target = (eo["targetPath"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val snap = (eo["snapshotPath"] as? JsonPrimitive)?.content.orEmpty()
                    val created = (eo["createdNew"] as? JsonPrimitive)?.booleanOrNull ?: false
                    if (!created && !File(snap).exists()) return@mapNotNull null
                    RollbackEntry(target, snap, created)
                }.orEmpty()
                if (entries.isEmpty()) return@mapNotNull null
                QuickRollbackSession(
                    id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                    timestampMs = (obj["timestampMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    packageName = (obj["packageName"] as? JsonPrimitive)?.content.orEmpty(),
                    uid = (obj["uid"] as? JsonPrimitive)?.content.orEmpty(),
                    summary = (obj["summary"] as? JsonPrimitive)?.content.orEmpty(),
                    entries = entries
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveRollbackSession(
        packageName: String,
        uid: String,
        summary: String,
        entries: List<RollbackEntry>
    ): QuickRollbackSession = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val session = QuickRollbackSession(
            id = "rb_$now",
            timestampMs = now,
            packageName = packageName,
            uid = uid,
            summary = summary,
            entries = entries
        )
        val existing = loadRollbackHistory().toMutableList()
        existing.add(0, session)
        val trimmed = existing.take(10)
        // Prune expired snapshot files not referenced by retained sessions
        val keptPaths = trimmed.flatMap { s -> s.entries.map { it.snapshotPath } }.toSet()
        existing.drop(10).forEach { old ->
            old.entries.forEach { e ->
                if (e.snapshotPath.isNotBlank() && e.snapshotPath !in keptPaths) {
                    runCatching { File(e.snapshotPath).delete() }
                }
            }
        }
        writeRollbackHistory(trimmed)
        session
    }

    suspend fun rollbackSession(session: QuickRollbackSession): Result<Int> = withContext(Dispatchers.IO) {
        rollbackBatch(session.packageName, session.entries).onSuccess {
            val remaining = loadRollbackHistory().filterNot { it.id == session.id }
            writeRollbackHistory(remaining)
        }
    }

    suspend fun launchGame(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext false
        val direct = runCatching {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
        if (direct) return@withContext true
        rootProber.execRoot("monkey -p ${ShellEscaper.quote(packageName)} -c android.intent.category.LAUNCHER 1").isSuccess
    }

    suspend fun createQuickSkdbBackup(
        scan: QuickModScanResult,
        profile: GameProfile
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val stageDir = File(context.cacheDir, "quick_bk_${System.nanoTime()}").apply { mkdirs() }
            try {
                val t = scan.targets
                val filesToPack = listOfNotNull(
                    t.gameFile, t.itemFile, t.weaponEvolutionFile, t.statisticFile,
                    t.seasonFile, t.escapeSeasonFile, t.monsriseFile, t.miscFile
                ).distinct()
                val stagedGameFiles = linkedMapOf<String, File>()
                for (path in filesToPack) {
                    val name = File(path).name
                    rootBackend.readBytes(path).onSuccess { bytes ->
                        val local = File(stageDir, name)
                        local.writeBytes(bytes)
                        stagedGameFiles[name] = local
                    }
                }
                check(stagedGameFiles.isNotEmpty()) { "Không có tệp save nào để sao lưu" }

                var prefsName: String? = null
                var prefsLocalFile: File? = null
                t.prefsFile?.let { pPath ->
                    rootBackend.readBytes(pPath).onSuccess { bytes ->
                        val name = File(pPath).name
                        val local = File(stageDir, name)
                        local.writeBytes(bytes)
                        prefsName = name
                        prefsLocalFile = local
                    }
                }

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val uidTag = scan.selectedUid.ifBlank { "guest" }
                val outFile = File(storagePaths.getBackupsDir(), "auto_UID_${uidTag}_$ts${RustCoreEngine.SKDB_EXTENSION}")
                rustCore.createBackupFromStaging(
                    targetFile = outFile,
                    profile = profile,
                    packageName = scan.packageName,
                    stagedGameFiles = stagedGameFiles,
                    sharedPrefsFileName = prefsName,
                    stagedSharedPrefsFile = prefsLocalFile,
                    sanitizeAccountData = false,
                    appVersion = "1.1.0"
                )
                outFile.absolutePath
            } finally {
                stageDir.deleteRecursively()
            }
        }
    }

    private fun writeRollbackHistory(sessions: List<QuickRollbackSession>) {
        val arr = JsonArray(sessions.map { s ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(s.id),
                    "timestampMs" to JsonPrimitive(s.timestampMs),
                    "packageName" to JsonPrimitive(s.packageName),
                    "uid" to JsonPrimitive(s.uid),
                    "summary" to JsonPrimitive(s.summary),
                    "entries" to JsonArray(s.entries.map { e ->
                        JsonObject(
                            mapOf(
                                "targetPath" to JsonPrimitive(e.targetPath),
                                "snapshotPath" to JsonPrimitive(e.snapshotPath),
                                "createdNew" to JsonPrimitive(e.createdNew)
                            )
                        )
                    })
                )
            )
        })
        File(storagePaths.getQuickRollbackDir(), "history.json").writeText(arr.toString(), Charsets.UTF_8)
    }

    private suspend fun loadAvailableHeroes(
        profile: GameProfile,
        gamePath: String?,
        catalog: com.dmedit.core.model.ItemCatalog?
    ): List<HeroOption> {
        val liveKeys = if (gamePath != null) {
            runCatching {
                val raw = rootBackend.readBytes(gamePath).getOrThrow()
                val text = rustCore.decryptBytes(profile, "sk_xor", raw).toString(Charsets.UTF_8)
                val obj = json.parseToJsonElement(text) as? JsonObject
                (obj?.get("heroUnlock") as? JsonObject)?.keys?.toList().orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val keys = liveKeys.ifEmpty {
            catalog?.items?.filterValues { it.category == "hero" }?.keys?.toList().orEmpty()
        }
        return keys.map { key ->
            val item = catalog?.items?.get(key)
            HeroOption(
                key = key,
                displayName = item?.nameVi ?: key,
                nameEn = item?.nameEn ?: key
            )
        }
    }

    companion object {
        private const val MINIMAL_ESCAPE_TEMPLATE =
            """{"version":1,"maxRaidSessionId":1,"money":0,"playerBag":{"slotCount":30,"slots":[]},"safeSlots":{"slotCount":1,"slots":[]},"weaponSlots":{"slotCount":2,"slots":[]},"consumableSlots":{"slotCount":3,"slots":[]},"bagSlots":{"slotCount":1,"slots":[]},"armorSlots":{"slotCount":1,"slots":[]},"talismanSlots":{"slotCount":1,"slots":[]},"warehouse":{"slotCount":120,"slots":[]},"buildings":[],"upgradeBlueprints":[],"unlockedCraftBlueprintIds":[],"pendingCraftBlueprintItemIds":[],"unlockedHeroes":[],"rescuedHeroes":[],"rescuedNpcIds":[],"achievedNodeKeys":[],"markedItemIds":[],"activatedBeacons":[],"submittedBeacons":[],"builtBridgeKeys":[],"visitedEscapeSceneNames":[],"firstKilledEnemySpawnerKeys":[],"explorerTreasureMapLastClaimDay":-1,"hasEscapedTutorial":true,"selectedHeroInitialized":true,"selectedHero":0,"selectedSkin":0,"selectedSkill":0,"recentSkinByHero":{},"hasPendingHeroLoss":false,"pendingHeroLossHero":-1,"totalIronMoneyEarned":0,"escapeGoldCupClaimedRewardLevel":0,"hasPlayedEndScene":false}"""
        private const val MINIMAL_MONSRISE_TEMPLATE =
            """{"version":1,"lastCharacterIdx":0,"lastCharacterSkinIdx":0,"maxClearLevel":0,"lastLevelIndex":0,"unlockedContents":[],"pveGameTimes":1,"usingTeamIdx":0,"monsterTeams":[],"weeklyExchangeTimeRecord":{},"weeklyExchangeRecords":[],"monsterShopUniqueSeed":100000,"monsterChestOpenTimesTotal":0,"monsterChestOpenTimesDaily":0,"CollectedMonsters":{},"replayList":[],"CollectedEmblems":{}}"""
        private const val MINIMAL_MISC_TEMPLATE =
            """{"returningEventStartTimestamp":0,"returningEventCurrentDay":0,"returningEventLastUnlockDate":"","isReturnPlayerInit":false,"returnPlayerTrailHero":-1,"returnPlayerTrailHeroStartTimestamp":0,"returnPlayerFactorCount":0,"returnPlayerRewardList":[],"returnPlayerRewardList790":[],"returnPlayerSelectSkinName":"","lastCheckInDay":0,"lastPopupGameFinishDay":0,"currentReturnCount":0,"hasGetFactorJuly":false,"hasGetFactorAugust":false,"unlockedKillEffectIds":[],"equippedKillEffectIdByScene":[]}"""
    }

    private fun validateAndMinifyJson(doc: String, label: String): String {
        val valRes = rustCore.validateContent(doc, com.dmedit.core.model.DataFormat.JSON)
        check(valRes.isValid) { "Kiểm tra cấu trúc JSON thất bại cho $label: ${valRes.errorMessage}" }
        return rustCore.minifyJson(doc).getOrThrow()
    }

    private fun encryptAndVerifyRoundTrip(
        profile: GameProfile,
        codec: String,
        minifiedJson: String,
        originalRaw: ByteArray,
        label: String
    ): ByteArray {
        val plainBytes = minifiedJson.toByteArray(Charsets.UTF_8)
        val encoded = rustCore.encryptBytes(profile, codec, plainBytes, originalRaw)
        val decodedCheck = rustCore.decryptBytes(profile, codec, encoded)
        check(decodedCheck.contentEquals(plainBytes)) {
            "Kiểm chứng mã hóa ngược thất bại cho $label ($codec) — đã hủy ghi để bảo vệ save!"
        }
        return encoded
    }

    private suspend fun loadSoulKnightBundle(): LoadedProfileBundle? {
        val list = profileRepository.profiles.value.ifEmpty { profileRepository.loadAllProfiles() }
        return list.firstOrNull { it.profile.id == "chillyroom.soul_knight" }
    }

    private fun resolveTargetsForUid(
        filesDir: String,
        fileNames: Set<String>,
        prefsPath: String?,
        uid: String,
        preferModern: Boolean
    ): QuickModTargets {
        fun pickShard(base: String): String? {
            val candidates = buildList {
                if (uid.isNotBlank()) {
                    if (preferModern) {
                        add("${base}_${uid}_.data.new")
                        add("${base}_${uid}_.data.rij")
                        add("${base}_${uid}_.data")
                    } else {
                        add("${base}_${uid}_.data")
                        add("${base}_${uid}_.data.new")
                        add("${base}_${uid}_.data.rij")
                    }
                } else {
                    if (preferModern) {
                        add("$base.data.new")
                        add("$base.data.rij")
                        add("$base.data")
                    } else {
                        add("$base.data")
                        add("$base.data.new")
                        add("$base.data.rij")
                    }
                }
            }
            val found = candidates.firstOrNull { c -> fileNames.any { it.equals(c, ignoreCase = true) } }
                ?.let { matched -> fileNames.first { it.equals(matched, ignoreCase = true) } }
            return found?.let { "$filesDir/$it" }
        }

        val gameFile = fileNames.firstOrNull { it.equals("game.data", ignoreCase = true) }
            ?.let { "$filesDir/$it" }

        return QuickModTargets(
            gameFile = gameFile,
            prefsFile = prefsPath,
            itemFile = pickShard("item_data"),
            weaponEvolutionFile = pickShard("weapon_evolution_data"),
            statisticFile = pickShard("statistic"),
            seasonFile = pickShard("season_data"),
            escapeSeasonFile = pickShard("escape_season_data"),
            monsriseFile = pickShard("monsrise_data"),
            miscFile = pickShard("misc_data")
        )
    }

    private fun isModernPath(path: String): Boolean =
        path.endsWith(".data.new", ignoreCase = true) || path.endsWith(".data.rij", ignoreCase = true)
}
