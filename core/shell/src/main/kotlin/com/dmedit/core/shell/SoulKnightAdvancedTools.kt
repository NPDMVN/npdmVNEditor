package com.dmedit.core.shell

import android.content.Context
import com.dmedit.core.model.DataFormat
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthSeverity { OK, WARNING, CRITICAL }

data class SaveHealthItem(
    val title: String,
    val detail: String,
    val severity: HealthSeverity,
    val autoFixable: Boolean = false
)

data class SaveHealthReport(
    val uid: String,
    val items: List<SaveHealthItem>
) {
    val criticalCount: Int get() = items.count { it.severity == HealthSeverity.CRITICAL }
    val warningCount: Int get() = items.count { it.severity == HealthSeverity.WARNING }
    val hasFixableIssues: Boolean get() = items.any { it.autoFixable && it.severity != HealthSeverity.OK }
}

data class SaveHealthRepairResult(
    val fixedFilesCount: Int,
    val summaryLines: List<String>,
    val rollbackSession: QuickRollbackSession? = null
)

data class UidCloneResult(
    val copiedFilesCount: Int,
    val syncedPrefsKeys: Int,
    val sourceUid: String,
    val targetUid: String,
    val logs: List<String>,
    val rollbackSession: QuickRollbackSession? = null
)

data class PresetExtractResult(
    val extractedGroups: Int,
    val totalEntries: Int,
    val details: List<String>,
    val overrideInfo: PresetOverrideInfo
)

@Singleton
class SoulKnightAdvancedTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootBackend: AndroidRootBackend,
    private val profileRepository: ProfileRepository,
    private val rustCore: RustCoreEngine,
    private val formatSyncer: SoulKnightFormatSyncer,
    private val quickEngine: SoulKnightQuickPatchEngine
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val uidShardRegex = Regex("^([a-z0-9_]+)_([0-9]+|[0-9a-f]{16})_(\\.data(?:\\.(?:new|rij))?)$", RegexOption.IGNORE_CASE)

    // =========================================================================
    // 1. SAVE HEALTH CHECK & 1-TAP STRUCTURAL REPAIR
    // =========================================================================
    suspend fun auditSaveHealth(scan: QuickModScanResult): SaveHealthReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<SaveHealthItem>()
        if (!scan.hasRoot) {
            items += SaveHealthItem("Quyền Root", "Chưa có quyền Root để kiểm tra dữ liệu.", HealthSeverity.CRITICAL)
            return@withContext SaveHealthReport(scan.selectedUid, items)
        }
        val bundle = loadSoulKnightBundle() ?: run {
            items += SaveHealthItem("Profile", "Không tìm thấy profile Soul Knight.", HealthSeverity.CRITICAL)
            return@withContext SaveHealthReport(scan.selectedUid, items)
        }
        val profile = bundle.profile
        val uid = scan.selectedUid
        val prefix = if (uid.isBlank()) "" else "${uid}_"
        val t = scan.targets

        // Check 1: Default no-UID shadow files alongside logged-in UID
        if (uid.isNotBlank() && scan.defaultShadowFiles.isNotEmpty()) {
            items += SaveHealthItem(
                title = "Phát hiện ${scan.defaultShadowFiles.size} file mặc định không UID",
                detail = "Tồn tại song song với UID $uid: ${scan.defaultShadowFiles.take(4).joinToString(", ")}${if (scan.defaultShadowFiles.size > 4) "…" else ""}. App đã tự khóa tránh chạm vào các file này — lưu ý không sửa tay chúng!",
                severity = HealthSeverity.WARNING,
                autoFixable = false
            )
        } else if (uid.isNotBlank()) {
            items += SaveHealthItem(
                title = "Khóa đúng UID $uid",
                detail = "Không có tệp shard mặc định không UID gây nhầm lẫn.",
                severity = HealthSeverity.OK
            )
        }

        // Check 2: game.data & playerprefs.xml structural & parity checks
        val prefsMap = formatSyncer.readPrefsSnapshot(profile, scan.packageName)?.second.orEmpty()
        var gameObj: JsonObject? = null
        if (t.gameFile != null) {
            runCatching {
                val raw = rootBackend.readBytes(t.gameFile).getOrThrow()
                val text = rustCore.decryptBytes(profile, "sk_xor", raw).toString(Charsets.UTF_8)
                gameObj = json.parseToJsonElement(text) as? JsonObject
            }.onFailure { err ->
                items += SaveHealthItem(
                    title = "game.data lỗi giải mã",
                    detail = err.message ?: "Không đọc được game.data",
                    severity = HealthSeverity.CRITICAL
                )
            }
        }

        gameObj?.let { g ->
            val gems = (g["gems"] as? JsonPrimitive)?.longOrNull ?: 0L
            val lastGems = (g["lastGems"] as? JsonPrimitive)?.longOrNull ?: gems
            val varGems = (g["varGems"] as? JsonPrimitive)?.longOrNull ?: 0L
            val cumGems = (g["cumulativeGems"] as? JsonPrimitive)?.longOrNull ?: 0L
            val fishChips = (g["fishChip"] as? JsonPrimitive)?.longOrNull ?: 0L

            val prefsGems = prefsMap["${prefix}gems"]?.toLongOrNull()
            val prefsLast = prefsMap["${prefix}last_gems"]?.toLongOrNull()
            val prefsVar = prefsMap["${prefix}var_gems"]?.toLongOrNull() ?: 0L
            val prefsCum = prefsMap["${prefix}cumulative_gems"]?.toLongOrNull() ?: 0L

            val gemIssues = mutableListOf<String>()
            if (gems != lastGems) gemIssues += "gems ($gems) ≠ lastGems ($lastGems)"
            if (varGems != 0L) gemIssues += "varGems = $varGems (nên là 0)"
            if (cumGems < gems) gemIssues += "cumulativeGems ($cumGems) < gems ($gems)"
            if (prefsGems != null && prefsGems != gems) gemIssues += "playerprefs gems ($prefsGems) ≠ game.data ($gems)"
            if (prefsLast != null && prefsLast != gems) gemIssues += "playerprefs last_gems ($prefsLast) ≠ $gems"
            if (prefsVar != 0L) gemIssues += "playerprefs var_gems = $prefsVar"
            if (prefsCum > 0L && prefsCum < gems) gemIssues += "playerprefs cumulative_gems ($prefsCum) < $gems"

            if (gemIssues.isNotEmpty()) {
                items += SaveHealthItem(
                    title = "Lệch đồng bộ Đá quý (game.data ↔ playerprefs.xml)",
                    detail = gemIssues.joinToString("; "),
                    severity = HealthSeverity.CRITICAL,
                    autoFixable = true
                )
            } else {
                items += SaveHealthItem(
                    title = "Đồng bộ Đá quý chuẩn ($gems Gems)",
                    detail = "gems == lastGems, varGems == 0, cumulativeGems ≥ gems khớp cả 2 nơi.",
                    severity = HealthSeverity.OK
                )
            }

            if (fishChips > 100L) {
                items += SaveHealthItem(
                    title = "Cá khô (Fish Chips) = $fishChips",
                    detail = "Cá khô cao bất thường so với tài khoản chưa nạp có thể bị máy chủ đối chiếu biên lai IAP khi tải Cloud.",
                    severity = HealthSeverity.WARNING,
                    autoFixable = false
                )
            }

            val heroLevels = g["heroLevel"] as? JsonObject
            val overMaxHeroes = heroLevels?.entries?.filter { (_, v) -> ((v as? JsonPrimitive)?.intOrNull ?: 0) > 7 }.orEmpty()
            if (overMaxHeroes.isNotEmpty()) {
                items += SaveHealthItem(
                    title = "${overMaxHeroes.size} nhân vật vượt trần cấp Lv.7",
                    detail = "Trần hợp lệ của Soul Knight là Lv.7 — có thể bấm sửa tự động để hạ về đúng Lv.7.",
                    severity = HealthSeverity.CRITICAL,
                    autoFixable = true
                )
            } else {
                items += SaveHealthItem(
                    title = "Cấp độ nhân vật hợp lệ (≤ Lv.7)",
                    detail = "Không có nhân vật nào vượt ngưỡng chuẩn game.",
                    severity = HealthSeverity.OK
                )
            }
        }

        // Check 3: item_data_<UID>_
        if (t.itemFile != null) {
            val isModern = isModernPath(t.itemFile)
            val codec = if (isModern) "sk_aes_v2" else "sk_des_default"
            runCatching {
                val raw = rootBackend.readBytes(t.itemFile).getOrThrow()
                val text = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                val itObj = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching

                val mats = itObj["materials"] as? JsonObject
                val badMats = mats?.keys?.filter { it == "material_bp_point" || it.startsWith("material_activity_coin") }.orEmpty()
                if (badMats.isNotEmpty()) {
                    items += SaveHealthItem(
                        title = "Có ${badMats.size} vật phẩm BP/sự kiện server trong kho",
                        detail = "Khóa: ${badMats.joinToString(", ")} — nên xóa để tránh lệch kiểm tra sự kiện server.",
                        severity = HealthSeverity.WARNING,
                        autoFixable = true
                    )
                }

                val jewelry = itObj["jewelryData"] as? JsonObject
                val badDurability = jewelry?.entries?.count { (_, el) ->
                    val dur = ((el as? JsonObject)?.get("durability") as? JsonPrimitive)?.intOrNull ?: 100
                    dur > 100
                } ?: 0
                if (badDurability > 0) {
                    items += SaveHealthItem(
                        title = "$badDurability trang sức có độ bền > 100",
                        detail = "Độ bền tối đa hợp lệ trong game là 100 — nên chuẩn hóa về 100.",
                        severity = HealthSeverity.WARNING,
                        autoFixable = true
                    )
                }

                if (badMats.isEmpty() && badDurability == 0) {
                    items += SaveHealthItem(
                        title = "Kho đồ & Trang sức (${File(t.itemFile).name}) sạch",
                        detail = "Không có vật phẩm BP/sự kiện bất hợp lệ, độ bền trang sức chuẩn ≤ 100.",
                        severity = HealthSeverity.OK
                    )
                }
            }.onFailure { err ->
                items += SaveHealthItem("Lỗi đọc item_data", err.message.orEmpty(), HealthSeverity.CRITICAL)
            }
        }

        // Check 4: statistic_<UID>_
        if (t.statisticFile != null) {
            val codec = if (isModernPath(t.statisticFile)) "sk_aes_v2" else "sk_des_statistic"
            runCatching {
                val raw = rootBackend.readBytes(t.statisticFile).getOrThrow()
                val text = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                val statRoot = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching
                val statData = statRoot["StatisticsData"] as? JsonObject
                val levelStat = statData?.get("LevelGameStatisticData") as? JsonObject
                val weaponStat = statData?.get("WeaponGameStatisticData") as? JsonObject
                val highest = levelStat?.get("_highestLevelArrived") as? JsonObject

                val overflowKeys = highest?.entries?.filter { (_, v) -> ((v as? JsonPrimitive)?.longOrNull ?: 0L) > 10_000L }.orEmpty()
                val missingType = (weaponStat != null && !weaponStat.containsKey("\$type")) ||
                    (levelStat != null && !levelStat.containsKey("\$type"))

                if (overflowKeys.isNotEmpty() || missingType) {
                    val reasons = mutableListOf<String>()
                    if (overflowKeys.isNotEmpty()) reasons += "${overflowKeys.size} chỉ số tầng tràn số (> 10.000)"
                    if (missingType) reasons += "thiếu trường \$type chuẩn Newtonsoft.Json"
                    items += SaveHealthItem(
                        title = "Cấu trúc statistic cần chuẩn hóa",
                        detail = reasons.joinToString("; "),
                        severity = HealthSeverity.WARNING,
                        autoFixable = true
                    )
                } else {
                    items += SaveHealthItem(
                        title = "Thống kê & Bàn Rèn (${File(t.statisticFile).name}) chuẩn",
                        detail = "Không bị tràn số tầng, đầy đủ \$type chuẩn Soul Knight 8.x.",
                        severity = HealthSeverity.OK
                    )
                }
            }.onFailure { err ->
                items += SaveHealthItem("Lỗi đọc statistic", err.message.orEmpty(), HealthSeverity.CRITICAL)
            }
        }

        SaveHealthReport(uid = uid, items = items)
    }

    suspend fun repairSaveHealth(scan: QuickModScanResult): Result<SaveHealthRepairResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(scan.hasRoot) { "Cần quyền Root để sửa cấu trúc save" }
            val bundle = loadSoulKnightBundle() ?: error("Không tải được profile Soul Knight")
            val profile = bundle.profile
            val t = scan.targets

            rootBackend.forceStopPackage(scan.packageName)
            val rollbacks = mutableListOf<RollbackEntry>()
            val summary = mutableListOf<String>()
            var fixedFiles = 0

            // 1. Repair game.data + re-sync playerprefs.xml
            if (t.gameFile != null) {
                val raw = rootBackend.readBytes(t.gameFile).getOrThrow()
                var doc = rustCore.decryptBytes(profile, "sk_xor", raw).toString(Charsets.UTF_8)
                val g = json.parseToJsonElement(doc) as? JsonObject
                if (g != null) {
                    val gems = (g["gems"] as? JsonPrimitive)?.longOrNull ?: 0L
                    val cum = (g["cumulativeGems"] as? JsonPrimitive)?.longOrNull ?: 0L
                    doc = rustCore.patchJsonPath(doc, "$.lastGems", JsonPrimitive(gems))
                    doc = rustCore.patchJsonPath(doc, "$.varGems", JsonPrimitive(0))
                    doc = rustCore.patchJsonPath(doc, "$.cumulativeGems", JsonPrimitive(maxOf(cum, gems)))

                    val levels = g["heroLevel"] as? JsonObject
                    if (levels != null && levels.values.any { ((it as? JsonPrimitive)?.intOrNull ?: 0) > 7 }) {
                        val clamped = JsonObject(levels.mapValues { (_, el) ->
                            val cur = (el as? JsonPrimitive)?.intOrNull ?: 0
                            JsonPrimitive(cur.coerceIn(0, 7))
                        })
                        doc = rustCore.patchJsonPath(doc, "$.heroLevel", clamped)
                    }

                    val minified = validateAndMinify(doc, "game.data")
                    val encoded = encryptAndVerify(profile, "sk_xor", minified, raw, "game.data")
                    val snap = rootBackend.writeBytesTransactional(t.gameFile, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(t.gameFile, snap.absolutePath)
                    fixedFiles++

                    val sync = formatSyncer.syncGameDataToPlayerPrefs(
                        gameJsonText = minified,
                        profile = profile,
                        packageName = scan.packageName,
                        currentFilePath = t.gameFile,
                        forcedAccountUid = scan.selectedUid
                    ).getOrThrow()
                    sync.rollbackPath?.let { rollbacks += RollbackEntry(sync.prefsPath, it) }
                    if (sync.updatedKeysCount > 0) fixedFiles++
                    summary += "✓ Cân bằng game.data ↔ playerprefs.xml (${sync.updatedKeysCount} khóa)"
                }
            }

            // 2. Repair item_data_<UID>_
            if (t.itemFile != null) {
                val fileName = File(t.itemFile).name
                val codec = if (isModernPath(t.itemFile)) "sk_aes_v2" else "sk_des_default"
                val raw = rootBackend.readBytes(t.itemFile).getOrThrow()
                var doc = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                val itObj = json.parseToJsonElement(doc) as? JsonObject
                var changed = false

                val mats = itObj?.get("materials") as? JsonObject
                if (mats != null && mats.keys.any { it == "material_bp_point" || it.startsWith("material_activity_coin") }) {
                    val cleaned = JsonObject(mats.filterKeys { it != "material_bp_point" && !it.startsWith("material_activity_coin") })
                    doc = rustCore.patchJsonPath(doc, "$.materials", cleaned)
                    changed = true
                }
                val jew = itObj?.get("jewelryData") as? JsonObject
                if (jew != null && jew.values.any { (((it as? JsonObject)?.get("durability") as? JsonPrimitive)?.intOrNull ?: 100) > 100 }) {
                    val fixedJew = JsonObject(jew.mapValues { (_, el) ->
                        val o = el as? JsonObject ?: return@mapValues el
                        val d = (o["durability"] as? JsonPrimitive)?.intOrNull ?: 100
                        if (d > 100) JsonObject(o + ("durability" to JsonPrimitive(100))) else o
                    })
                    doc = rustCore.patchJsonPath(doc, "$.jewelryData", fixedJew)
                    changed = true
                }

                if (changed) {
                    val minified = validateAndMinify(doc, fileName)
                    val encoded = encryptAndVerify(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(t.itemFile, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(t.itemFile, snap.absolutePath)
                    fixedFiles++
                    summary += "✓ Đã làm sạch vật phẩm BP/sự kiện & chuẩn hóa độ bền Trang sức = 100"
                }
            }

            // 3. Repair statistic_<UID>_
            if (t.statisticFile != null) {
                val fileName = File(t.statisticFile).name
                val codec = if (isModernPath(t.statisticFile)) "sk_aes_v2" else "sk_des_statistic"
                val raw = rootBackend.readBytes(t.statisticFile).getOrThrow()
                var doc = rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)
                val statRoot = json.parseToJsonElement(doc) as? JsonObject
                val statData = statRoot?.get("StatisticsData") as? JsonObject
                var changed = false

                val levelStat = statData?.get("LevelGameStatisticData") as? JsonObject
                if (levelStat != null) {
                    val highest = levelStat["_highestLevelArrived"] as? JsonObject
                    if (highest != null && highest.values.any { ((it as? JsonPrimitive)?.longOrNull ?: 0L) > 10_000L }) {
                        val fixedH = JsonObject(highest.mapValues { (_, el) ->
                            val cur = (el as? JsonPrimitive)?.longOrNull ?: 0L
                            if (cur > 10_000L) JsonPrimitive(20) else el
                        })
                        doc = rustCore.patchJsonPath(doc, "$.StatisticsData.LevelGameStatisticData._highestLevelArrived", fixedH)
                        changed = true
                    }
                    if (!levelStat.containsKey("\$type")) {
                        doc = rustCore.patchJsonPath(doc, "$.StatisticsData.LevelGameStatisticData.\$type", JsonPrimitive("GameStatisticSystem.LevelGameStatisticData"))
                        changed = true
                    }
                }
                val weaponStat = statData?.get("WeaponGameStatisticData") as? JsonObject
                if (weaponStat != null && !weaponStat.containsKey("\$type")) {
                    doc = rustCore.patchJsonPath(doc, "$.StatisticsData.WeaponGameStatisticData.\$type", JsonPrimitive("GameStatisticSystem.WeaponGameStatisticData"))
                    changed = true
                }

                if (changed) {
                    val minified = validateAndMinify(doc, fileName)
                    val encoded = encryptAndVerify(profile, codec, minified, raw, fileName)
                    val snap = rootBackend.writeBytesTransactional(t.statisticFile, encoded, null, createRollback = true).getOrThrow()
                    if (snap != null) rollbacks += RollbackEntry(t.statisticFile, snap.absolutePath)
                    fixedFiles++
                    summary += "✓ Chuẩn hóa chỉ số tầng & \$type trong $fileName"
                }
            }

            val session = if (rollbacks.isNotEmpty()) {
                quickEngine.saveRollbackSession(
                    packageName = scan.packageName,
                    uid = scan.selectedUid,
                    summary = "Sửa lỗi sức khỏe Save ($fixedFiles tệp)",
                    entries = rollbacks
                )
            } else null

            SaveHealthRepairResult(
                fixedFilesCount = fixedFiles,
                summaryLines = summary.ifEmpty { listOf("✓ Save đã chuẩn cấu trúc, không cần sửa thêm!") },
                rollbackSession = session
            )
        }
    }

    // =========================================================================
    // 2. CLONE / RE-UID SAVE (LOCAL UID -> TARGET UID OR .SKDB -> TARGET UID)
    // =========================================================================
    suspend fun cloneSaveBetweenUids(
        scan: QuickModScanResult,
        sourceUid: String,
        targetUid: String
    ): Result<UidCloneResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(scan.hasRoot) { "Cần quyền Root để chuyển UID" }
            require(sourceUid.isNotBlank() && targetUid.isNotBlank()) { "Cần chọn cả UID nguồn và UID đích" }
            require(!sourceUid.equals(targetUid, ignoreCase = true)) { "UID nguồn và UID đích phải khác nhau" }

            val bundle = loadSoulKnightBundle() ?: error("Không tải được profile Soul Knight")
            val profile = bundle.profile
            rootBackend.forceStopPackage(scan.packageName)

            val entries = rootBackend.listDirectory(scan.filesDir, profile).getOrThrow()
            val rollbacks = mutableListOf<RollbackEntry>()
            val logs = mutableListOf<String>()
            var copied = 0

            for (entry in entries) {
                if (entry.stat.isDirectory) continue
                val m = uidShardRegex.matchEntire(entry.name) ?: continue
                val base = m.groupValues[1]
                val uid = m.groupValues[2]
                val ext = m.groupValues[3]
                if (!uid.equals(sourceUid, ignoreCase = true)) continue

                val targetName = "${base}_${targetUid}_$ext"
                val targetPath = "${scan.filesDir}/$targetName"
                val existedBefore = rootBackend.exists(targetPath)
                var bytes = rootBackend.readBytes(entry.fullPath).getOrThrow()

                // Update embedded ChillyUid inside setting_<UID>_ if present
                if (base.equals("setting", ignoreCase = true)) {
                    val codec = if (isModernPath(entry.name)) "sk_aes_v2" else "sk_des_default"
                    runCatching {
                        val txt = rustCore.decryptBytes(profile, codec, bytes).toString(Charsets.UTF_8)
                        val patched = rustCore.patchJsonPath(txt, "$.ChillyUid", JsonPrimitive(targetUid))
                        val min = validateAndMinify(patched, targetName)
                        bytes = encryptAndVerify(profile, codec, min, bytes, targetName)
                    }
                }

                val snap = rootBackend.writeBytesTransactional(targetPath, bytes, null, createRollback = existedBefore).getOrThrow()
                if (!existedBefore) {
                    rollbacks += RollbackEntry(targetPath, "", createdNew = true)
                } else if (snap != null) {
                    rollbacks += RollbackEntry(targetPath, snap.absolutePath)
                }
                copied++
                logs += "✓ ${entry.name} → $targetName"
            }

            val prefsRes = formatSyncer.clonePrefsBetweenUids(
                profile = profile,
                packageName = scan.packageName,
                sourceUid = sourceUid,
                targetUid = targetUid,
                preferModern = scan.preferModernFormat,
                sourcePrefsBytesOverride = null
            ).getOrThrow()
            prefsRes.rollbackPath?.let { rollbacks += RollbackEntry(prefsRes.prefsPath, it) }
            logs += "✓ playerprefs.xml: sao chép ${prefsRes.updatedKeysCount} khóa gameplay (đã lọc bỏ token/tài khoản cũ)"

            val session = if (rollbacks.isNotEmpty()) {
                quickEngine.saveRollbackSession(
                    packageName = scan.packageName,
                    uid = targetUid,
                    summary = "Chuyển Save UID $sourceUid → $targetUid ($copied tệp)",
                    entries = rollbacks
                )
            } else null

            UidCloneResult(
                copiedFilesCount = copied,
                syncedPrefsKeys = prefsRes.updatedKeysCount,
                sourceUid = sourceUid,
                targetUid = targetUid,
                logs = logs,
                rollbackSession = session
            )
        }
    }

    suspend fun importSkdbToActiveUid(
        scan: QuickModScanResult,
        skdbArchive: File,
        targetUid: String
    ): Result<UidCloneResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(scan.hasRoot) { "Cần quyền Root để nhập .skdb sang UID" }
            require(targetUid.isNotBlank()) { "Cần UID tài khoản đích đang đăng nhập" }
            require(skdbArchive.exists()) { "Không tìm thấy tệp sao lưu: ${skdbArchive.name}" }

            val bundle = loadSoulKnightBundle() ?: error("Không tải được profile Soul Knight")
            val profile = bundle.profile
            rootBackend.forceStopPackage(scan.packageName)

            val stageDir = File(context.cacheDir, "reuid_stage_${System.nanoTime()}").apply { mkdirs() }
            try {
                val extracted = rustCore.verifyAndExtractToStaging(skdbArchive, null, stageDir)
                val rollbacks = mutableListOf<RollbackEntry>()
                val logs = mutableListOf<String>()
                var copied = 0

                // Auto-detect source UID inside the backup archive
                val detectedSourceUid = extracted
                    .mapNotNull { uidShardRegex.matchEntire(File(it.targetRelativePath).name)?.groupValues?.getOrNull(2) }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    .orEmpty()

                var prefsBytes: ByteArray? = null
                for (item in extracted) {
                    val localFile = File(item.extractedDiskPath)
                    if (!localFile.exists()) continue
                    if (item.isSharedPrefs) {
                        prefsBytes = localFile.readBytes()
                        continue
                    }

                    val name = File(item.targetRelativePath).name
                    val targetName: String? = when {
                        name.equals("game.data", ignoreCase = true) -> "game.data"
                        else -> {
                            val m = uidShardRegex.matchEntire(name)
                            if (m != null && (detectedSourceUid.isEmpty() || m.groupValues[2].equals(detectedSourceUid, ignoreCase = true))) {
                                "${m.groupValues[1]}_${targetUid}_${m.groupValues[3]}"
                            } else {
                                null // Skip unrelated/default shadow files inside archive!
                            }
                        }
                    }

                    if (targetName == null) continue
                    val targetPath = "${scan.filesDir}/$targetName"
                    val existedBefore = rootBackend.exists(targetPath)
                    var bytes = localFile.readBytes()

                    if (targetName.startsWith("setting_", ignoreCase = true)) {
                        val codec = if (isModernPath(targetName)) "sk_aes_v2" else "sk_des_default"
                        runCatching {
                            val txt = rustCore.decryptBytes(profile, codec, bytes).toString(Charsets.UTF_8)
                            val patched = rustCore.patchJsonPath(txt, "$.ChillyUid", JsonPrimitive(targetUid))
                            val min = validateAndMinify(patched, targetName)
                            bytes = encryptAndVerify(profile, codec, min, bytes, targetName)
                        }
                    }

                    val snap = rootBackend.writeBytesTransactional(targetPath, bytes, null, createRollback = existedBefore).getOrThrow()
                    if (!existedBefore) {
                        rollbacks += RollbackEntry(targetPath, "", createdNew = true)
                    } else if (snap != null) {
                        rollbacks += RollbackEntry(targetPath, snap.absolutePath)
                    }
                    copied++
                    logs += "✓ $name → $targetName"
                }

                val prefsRes = formatSyncer.clonePrefsBetweenUids(
                    profile = profile,
                    packageName = scan.packageName,
                    sourceUid = detectedSourceUid,
                    targetUid = targetUid,
                    preferModern = scan.preferModernFormat,
                    sourcePrefsBytesOverride = prefsBytes
                ).getOrThrow()
                prefsRes.rollbackPath?.let { rollbacks += RollbackEntry(prefsRes.prefsPath, it) }
                logs += "✓ playerprefs.xml: nạp ${prefsRes.updatedKeysCount} khóa từ ${skdbArchive.name} sang UID $targetUid (đã lọc bỏ tài khoản cũ)"

                val session = if (rollbacks.isNotEmpty()) {
                    quickEngine.saveRollbackSession(
                        packageName = scan.packageName,
                        uid = targetUid,
                        summary = "Nhập ${skdbArchive.name} → UID $targetUid ($copied tệp)",
                        entries = rollbacks
                    )
                } else null

                UidCloneResult(
                    copiedFilesCount = copied,
                    syncedPrefsKeys = prefsRes.updatedKeysCount,
                    sourceUid = detectedSourceUid.ifBlank { "backup" },
                    targetUid = targetUid,
                    logs = logs,
                    rollbackSession = session
                )
            } finally {
                stageDir.deleteRecursively()
            }
        }
    }

    // =========================================================================
    // 3. LIVE SAVE PRESET EXTRACTOR (AUTO-UPDATE FOR SOUL KNIGHT 8.9 / 9.0+)
    // =========================================================================
    suspend fun extractPresetsFromLiveSave(scan: QuickModScanResult): Result<PresetExtractResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(scan.hasRoot) { "Cần quyền Root để quét dữ liệu save trên máy" }
            val bundle = loadSoulKnightBundle() ?: error("Không tải được profile Soul Knight")
            val profile = bundle.profile
            val t = scan.targets

            val extracted = linkedMapOf<String, JsonElement>()
            val details = mutableListOf<String>()

            // 1. item_data
            t.itemFile?.let { path ->
                val isModern = isModernPath(path)
                val codec = if (isModern) "sk_aes_v2" else "sk_des_default"
                runCatching {
                    val raw = rootBackend.readBytes(path).getOrThrow()
                    val obj = json.parseToJsonElement(rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)) as? JsonObject
                        ?: return@runCatching

                    (obj["materials"] as? JsonObject)?.let { m ->
                        val cleaned = m.entries
                            .filter { (k, _) -> k != "material_bp_point" && !k.startsWith("material_activity_coin") }
                            .associate { (k, _) ->
                                val qty = when {
                                    k.startsWith("material_tape_") || k.startsWith("material_zongzi_leaf_") -> 1
                                    k.startsWith("material_c") || k.startsWith("material_skill_") || k.startsWith("material_skin_fragment_") -> 20
                                    k == "material_jewelry_fragment" -> 99
                                    else -> 500
                                }
                                k to JsonPrimitive(qty)
                            }
                        if (cleaned.isNotEmpty()) {
                            extracted["materials_all"] = JsonObject(cleaned)
                            details += "Nguyên liệu: ${cleaned.size}"
                        }
                    }

                    (obj["seeds"] as? JsonObject)?.let { s ->
                        if (s.isNotEmpty()) {
                            extracted["seeds_all"] = JsonObject(s.keys.associateWith { JsonPrimitive(99) })
                            details += "Hạt giống: ${s.size}"
                        }
                    }

                    (obj["blueprints"] as? JsonObject)?.let { b ->
                        if (b.isNotEmpty()) {
                            extracted["blueprints_modern"] = JsonObject(b.keys.associateWith { JsonPrimitive(2) })
                            extracted["blueprints_legacy"] = JsonObject(b.keys.associateWith { JsonPrimitive("Researched") })
                            details += "Bản vẽ: ${b.size}"
                        }
                    }

                    (obj["tokenTickets"] as? JsonObject)?.let { tk ->
                        if (tk.isNotEmpty()) {
                            extracted["token_tickets_all"] = JsonObject(tk.keys.associateWith { JsonPrimitive(99) })
                            details += "Phiếu: ${tk.size}"
                        }
                    }

                    (obj["mythicWeapons"] as? JsonArray)?.let { mw ->
                        val norm = mw.mapNotNull { el ->
                            val id = ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                            JsonObject(mapOf("id" to JsonPrimitive(id), "level" to JsonPrimitive(3)))
                        }
                        if (norm.isNotEmpty()) {
                            extracted["mythic_weapons_all"] = JsonArray(norm)
                            details += "Vũ khí Thần thoại: ${norm.size}"
                        }
                    }

                    (obj["jewelryData"] as? JsonObject)?.let { jew ->
                        if (jew.isNotEmpty()) {
                            val norm = JsonObject(jew.mapValues { (_, v) ->
                                val o = v as? JsonObject ?: return@mapValues v
                                JsonObject(o + ("durability" to JsonPrimitive(100)))
                            })
                            extracted[if (isModern) "jewelry_modern" else "jewelry_legacy"] = norm
                            details += "Trang sức: ${jew.size}"
                        }
                    }

                    (obj["handStyleIndexList"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
                        extracted["hand_styles_all"] = it
                    }
                }
            }

            // 2. weapon_evolution_data
            t.weaponEvolutionFile?.let { path ->
                val codec = if (isModernPath(path)) "sk_aes_v2" else "sk_des_default"
                runCatching {
                    val raw = rootBackend.readBytes(path).getOrThrow()
                    val obj = json.parseToJsonElement(rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)) as? JsonObject
                    val weapons = obj?.get("weapons") as? JsonObject
                    if (weapons != null && weapons.isNotEmpty()) {
                        extracted["weapon_evolutions_all"] = weapons
                        details += "Vũ khí tiến hóa: ${weapons.size}"
                    }
                }
            }

            // 3. statistic (Forge Table & Cellar weapons from object2ObtainTime + object2PassLevel + _weaponUsedTimes)
            t.statisticFile?.let { path ->
                val codec = if (isModernPath(path)) "sk_aes_v2" else "sk_des_statistic"
                runCatching {
                    val raw = rootBackend.readBytes(path).getOrThrow()
                    val root = json.parseToJsonElement(rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)) as? JsonObject
                    val forgeKeys = linkedSetOf<String>()
                    fun collectWeaponKeys(obj: JsonObject?) {
                        obj?.keys?.forEach { k ->
                            val isSkinSuffix = Regex("_s_\\d+$").containsMatchIn(k)
                            if (k != "Any" && !isSkinSuffix &&
                                (k.startsWith("weapon_") || k.startsWith("ring_") || k.startsWith("transform_weapon_") ||
                                    k in setOf("ArtifactBow", "ArtifactStaff", "ArtifactSword"))
                            ) {
                                forgeKeys += k
                            }
                        }
                    }
                    collectWeaponKeys(root?.get("object2ObtainTime") as? JsonObject)
                    collectWeaponKeys(root?.get("object2PassLevel") as? JsonObject)
                    val used = ((root?.get("StatisticsData") as? JsonObject)?.get("WeaponGameStatisticData") as? JsonObject)
                        ?.get("_weaponUsedTimes") as? JsonObject
                    collectWeaponKeys(used)
                    if (forgeKeys.isNotEmpty()) {
                        extracted["forge_weapons_all"] = JsonObject(forgeKeys.associateWith { JsonPrimitive(8) })
                        details += "Bàn Rèn & Sổ tay vũ khí: ${forgeKeys.size}"
                    }
                }
            }

            // 4. season_data (Chips)
            t.seasonFile?.let { path ->
                val isModern = isModernPath(path)
                val codec = if (isModern) "sk_aes_v2" else "sk_des_default"
                runCatching {
                    val raw = rootBackend.readBytes(path).getOrThrow()
                    val obj = json.parseToJsonElement(rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)) as? JsonObject
                    val chips = obj?.get("chips") as? JsonArray
                    if (chips != null && chips.isNotEmpty()) {
                        val norm = JsonArray(chips.map { el ->
                            val o = el as? JsonObject ?: return@map el
                            JsonObject(o + ("level" to JsonPrimitive(10)) + ("refineLevel" to JsonPrimitive(10)))
                        })
                        extracted[if (isModern) "season_chips_modern" else "season_chips_legacy"] = norm
                        details += "Chip mùa giải: ${chips.size}"
                    }
                }
            }

            // 5. monsrise_data
            t.monsriseFile?.let { path ->
                val codec = if (isModernPath(path)) "sk_aes_v2" else "sk_des_default"
                runCatching {
                    val raw = rootBackend.readBytes(path).getOrThrow()
                    val obj = json.parseToJsonElement(rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)) as? JsonObject
                    (obj?.get("CollectedMonsters") as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { m ->
                        extracted["monsrise_monsters_all"] = JsonObject(m.keys.associateWith { JsonPrimitive(5) })
                        details += "Quái vật: ${m.size}"
                    }
                    (obj?.get("CollectedEmblems") as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { e ->
                        extracted["monsrise_emblems_all"] = JsonObject(e.keys.associateWith { JsonPrimitive(8) })
                        details += "Ấn ký: ${e.size}"
                    }
                    (obj?.get("unlockedContents") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
                        extracted["monsrise_unlocked_contents_all"] = it
                    }
                }
            }

            // 6. misc_data
            t.miscFile?.let { path ->
                val codec = if (isModernPath(path)) "sk_aes_v2" else "sk_des_default"
                runCatching {
                    val raw = rootBackend.readBytes(path).getOrThrow()
                    val obj = json.parseToJsonElement(rustCore.decryptBytes(profile, codec, raw).toString(Charsets.UTF_8)) as? JsonObject
                    (obj?.get("unlockedKillEffectIds") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
                        extracted["kill_effects_all"] = it
                        details += "Hiệu ứng kết liễu: ${it.size}"
                    }
                }
            }

            check(extracted.isNotEmpty()) { "Không trích xuất được vật phẩm mới nào từ save hiện tại" }
            val info = profileRepository.savePresetOverrideMap(profile.id, extracted).getOrThrow()
            PresetExtractResult(
                extractedGroups = extracted.size,
                totalEntries = info.totalEntriesCount,
                details = details,
                overrideInfo = info
            )
        }
    }

    private fun validateAndMinify(doc: String, label: String): String {
        val valRes = rustCore.validateContent(doc, DataFormat.JSON)
        check(valRes.isValid) { "Kiểm tra cấu trúc JSON thất bại cho $label: ${valRes.errorMessage}" }
        return rustCore.minifyJson(doc).getOrThrow()
    }

    private fun encryptAndVerify(
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
            "Kiểm chứng mã hóa ngược thất bại cho $label ($codec)!"
        }
        return encoded
    }

    private suspend fun loadSoulKnightBundle(): LoadedProfileBundle? {
        val list = profileRepository.profiles.value.ifEmpty { profileRepository.loadAllProfiles() }
        return list.firstOrNull { it.profile.id == "chillyroom.soul_knight" }
    }

    private fun isModernPath(path: String): Boolean =
        path.endsWith(".data.new", ignoreCase = true) || path.endsWith(".data.rij", ignoreCase = true)
}
