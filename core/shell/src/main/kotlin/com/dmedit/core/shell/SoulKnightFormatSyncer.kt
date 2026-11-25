package com.dmedit.core.shell

import com.dmedit.core.model.GameProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

data class NewFormatDiagnostic(
    val newFormatFiles: List<String> = emptyList(),
    val activeRijFlags: List<String> = emptyList(),
    val siblingGameDataFiles: List<String> = emptyList(),
    val isEditingLegacyWithModernPresent: Boolean = false,
    val movedBackupDir: String? = null,
    val movedBackupCount: Int = 0
) {
    val hasRijConflict: Boolean get() = isEditingLegacyWithModernPresent && (newFormatFiles.isNotEmpty() || activeRijFlags.isNotEmpty())
    val hasMovedBackupToRestore: Boolean get() = movedBackupDir != null && movedBackupCount > 0 && newFormatFiles.isEmpty()
    val hasBannerToShow: Boolean
        get() = hasRijConflict || siblingGameDataFiles.isNotEmpty() || hasMovedBackupToRestore
}

data class ClassicFormatRepairResult(
    val movedFilesCount: Int,
    val backupFolder: String,
    val clearedFlagsCount: Int
)

data class PlayerPrefsSyncResult(
    val updatedKeysCount: Int,
    val syncedAccounts: List<String>,
    val prefsPath: String,
    val rollbackPath: String? = null
)

data class RestoreNewFormatResult(
    val restoredFilesCount: Int,
    val enabledFlagsCount: Int
)

data class PrefsAccountSummary(
    val prefsPath: String?,
    val activeUidHint: String?,
    val observedUids: List<String>,
    val isModernRijEnabled: Boolean
)

/**
 * Soul Knight 6.x–8.x save synchronizer:
 * 1. Surgically mirrors `game.data` edits (heroUnlock, skinLock, heroLevel, heroSkillUnlock,
 *    petUnlock, gems, lastGems) into `com.ChillyRoom.DungeonShooter.v2.playerprefs.xml`
 *    while preserving untouched XML bytes verbatim.
 * 2. Detects legacy `.data` vs modern `.data.new` conflicts and supports safe 1-tap switching
 *    or restoring previously backed-up `.data.new` files.
 */
@Singleton
class SoulKnightFormatSyncer @Inject constructor(
    private val rootProber: AdaptiveRootProber,
    private val rootBackend: AndroidRootBackend,
    private val storagePaths: AppStoragePaths
) {
    private val json = Json { ignoreUnknownKeys = true }
    // Captures exact raw prefix including trailing '_' if present (e.g. "", "77614388_", "LOCAL_", "LOCAL", "a1b2..._")
    private val roleKeyRegex = Regex("^((?:[A-Za-z0-9-]+_|LOCAL)?)c(\\d+)_unlock$")
    private val skinKeyRegex = Regex("^((?:[A-Za-z0-9-]+_|LOCAL)?)c(\\d+)_skin(\\d+)$")

    suspend fun inspectNewFormatConflict(
        profile: GameProfile,
        packageName: String,
        currentFilePath: String? = null
    ): NewFormatDiagnostic = withContext(Dispatchers.IO) {
        val resolvedPkg = extractPackageFromPath(currentFilePath) ?: packageName
        if (resolvedPkg.isBlank()) return@withContext NewFormatDiagnostic()
        val rootInfo = rootProber.probe()
        if (!rootInfo.hasRoot) return@withContext NewFormatDiagnostic()

        val filesDir = if (!currentFilePath.isNullOrBlank() && currentFilePath.contains("/files/")) {
            File(currentFilePath).parent ?: rootBackend.resolveGameFilesDir(rootInfo.activeUserId, resolvedPkg, profile)
        } else {
            rootBackend.resolveGameFilesDir(rootInfo.activeUserId, resolvedPkg, profile)
        }
        val entries = rootBackend.listDirectory(filesDir, profile).getOrDefault(emptyList())
        val newFiles = entries.filter { e ->
            !e.stat.isDirectory &&
                (e.name.endsWith(".data.new", ignoreCase = true) || e.name.endsWith(".data.rij", ignoreCase = true))
        }.map { it.fullPath }

        val currentName = currentFilePath?.let { File(it).name }.orEmpty()
        val isEditingLegacyShard = currentName.endsWith(".data", ignoreCase = true) &&
            !currentName.equals("game.data", ignoreCase = true) &&
            newFiles.any { File(it).name.startsWith("$currentName.", ignoreCase = true) }

        val siblingGameFiles = if (currentName.equals("game.data", ignoreCase = true)) {
            entries.filter { e ->
                !e.stat.isDirectory &&
                    e.name.startsWith("game_", ignoreCase = true) &&
                    e.name.endsWith(".data", ignoreCase = true)
            }.map { it.name }
        } else {
            emptyList()
        }

        val activeFlags = mutableListOf<String>()
        val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, resolvedPkg, profile)
        if (prefsPath != null) {
            rootBackend.readBytes(prefsPath).onSuccess { bytes ->
                runCatching {
                    val nodes = parsePrefsNodes(bytes)
                    nodes.forEach { (name, el) ->
                        if ((name.startsWith("OpenRijTest") || name.startsWith("OpenNewtonJsonTest")) &&
                            el.getAttribute("value") == "1"
                        ) {
                            activeFlags += name
                        }
                    }
                }
            }
        }

        val latestMovedDir = findLatestMovedBackupDir()
        val movedCount = latestMovedDir?.listFiles { f ->
            f.isFile && (f.name.endsWith(".data.new", ignoreCase = true) || f.name.endsWith(".data.rij", ignoreCase = true))
        }?.size ?: 0

        NewFormatDiagnostic(
            newFormatFiles = newFiles,
            activeRijFlags = activeFlags,
            siblingGameDataFiles = siblingGameFiles,
            isEditingLegacyWithModernPresent = isEditingLegacyShard,
            movedBackupDir = latestMovedDir?.absolutePath,
            movedBackupCount = movedCount
        )
    }

    /**
     * Automatically mirrors edited `game.data` fields into `com.ChillyRoom.DungeonShooter.v2.playerprefs.xml`:
     * - `heroUnlock` -> `<prefix>c<idx>_unlock`
     * - `skinLock` -> `<prefix>c<idx>_skin<skinKey>`
     * - `heroLevel` -> `<prefix>c<idx>_level`
     * - `heroSkillUnlock` -> `<prefix>c_<HeroName>_skill_<skillKey>_unlock`
     * - `petUnlock` -> `<prefix>p<petIdx>_unlock`
     * - `gems` & `lastGems` -> `<prefix>gems` & `<prefix>last_gems`
     */
    suspend fun syncGameDataToPlayerPrefs(
        gameJsonText: String,
        profile: GameProfile,
        packageName: String,
        currentFilePath: String? = null,
        forcedAccountUid: String? = null
    ): Result<PlayerPrefsSyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedPkg = extractPackageFromPath(currentFilePath) ?: packageName
            val rootInfo = rootProber.probe()
            check(rootInfo.hasRoot) { "Cần quyền Root để đồng bộ playerprefs.xml" }

            val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, resolvedPkg, profile)
                ?: error("Không tìm thấy tệp playerprefs.xml trong shared_prefs của '$resolvedPkg'")

            val gameRoot = json.parseToJsonElement(gameJsonText) as? JsonObject
                ?: error("Nội dung game.data không phải JSON object")

            val origPrefsBytes = rootBackend.readBytes(prefsPath).getOrThrow()
            val origXmlText = origPrefsBytes.toString(Charsets.UTF_8)
            val nodes = parsePrefsNodes(origPrefsBytes)

            val targetPrefixes = if (forcedAccountUid != null) {
                resolvePrefixesForForcedUid(nodes, forcedAccountUid)
            } else {
                resolveTargetPrefixes(nodes)
            }
            val updates = linkedMapOf<String, XmlPatchOp>()

            // Build exact 0-based hero & pet index tables from game.data key insertion order
            val heroUnlockObj = gameRoot["heroUnlock"] as? JsonObject
            val heroIndexMap = linkedMapOf<String, Int>()
            heroUnlockObj?.keys?.forEachIndexed { idx, heroName -> heroIndexMap[heroName] = idx }

            val petUnlockObj = gameRoot["petUnlock"] as? JsonObject
            val petIndexMap = linkedMapOf<String, Int>()
            petUnlockObj?.keys?.forEachIndexed { idx, petName -> petIndexMap[petName] = idx }

            val skinLockObj = gameRoot["skinLock"] as? JsonObject
            val heroLevelObj = gameRoot["heroLevel"] as? JsonObject
            val heroSkillObj = gameRoot["heroSkillUnlock"] as? JsonObject
            val roomObjectLevelObj = gameRoot["roomObjectLevel"] as? JsonObject
            val gemsVal = (gameRoot["gems"] as? JsonPrimitive)?.longOrNull
            val lastGemsVal = gemsVal ?: (gameRoot["lastGems"] as? JsonPrimitive)?.longOrNull
            val cumulativeGemsVal = (gameRoot["cumulativeGems"] as? JsonPrimitive)?.longOrNull
            val fishChipVal = (gameRoot["fishChip"] as? JsonPrimitive)?.longOrNull
            val rebornCardVal = (gameRoot["rebornCard"] as? JsonPrimitive)?.booleanOrNull
            val hasTutorialVal = (gameRoot["hasTutorial"] as? JsonPrimitive)?.intOrNull
            val firstInRoomVal = (gameRoot["firstInRoom"] as? JsonPrimitive)?.intOrNull
            val totalIndexVal = (gameRoot["totalIndex"] as? JsonPrimitive)?.intOrNull
            val normalCertsArr = gameRoot["normalModePassCertificates"] as? JsonArray

            for (prefix in targetPrefixes) {

                // 1. Hero Unlocks: <prefix>c<idx>_unlock
                heroUnlockObj?.forEach { (heroName, el) ->
                    val unlocked = (el as? JsonPrimitive)?.booleanOrNull ?: return@forEach
                    val cidx = heroIndexMap[heroName] ?: return@forEach
                    val key = "${prefix}c${cidx}_unlock"
                    val existing = nodes[key]
                    if (existing != null) {
                        val newVal = formatBoolForExistingNode(existing, unlocked)
                        if (nodeValue(existing) != newVal) {
                            updates[key] = XmlPatchOp(existing.tagName, newVal, existing)
                        }
                    } else if (unlocked) {
                        updates[key] = XmlPatchOp("string", "True", null)
                    }
                }

                // 2. Skin Unlocks: <prefix>c<idx>_skin<skinKey>
                skinLockObj?.forEach { (heroName, el) ->
                    val cidx = heroIndexMap[heroName] ?: return@forEach
                    val arr = el as? JsonArray ?: return@forEach
                    for (entryEl in arr) {
                        val obj = entryEl as? JsonObject ?: continue
                        val skinKey = (obj["Key"] as? JsonPrimitive)?.intOrNull ?: continue
                        val skinVal = (obj["Value"] as? JsonPrimitive)?.intOrNull ?: continue
                        val key = "${prefix}c${cidx}_skin$skinKey"
                        val strVal = skinVal.toString()
                        val existing = nodes[key]
                        if (existing != null) {
                            if (nodeValue(existing) != strVal) {
                                updates[key] = XmlPatchOp(existing.tagName, strVal, existing)
                            }
                        } else if (skinVal == 1) {
                            updates[key] = XmlPatchOp("int", "1", null)
                        }
                    }
                }

                // 3. Hero Levels: <prefix>c<idx>_level
                heroLevelObj?.forEach { (heroName, el) ->
                    val level = (el as? JsonPrimitive)?.intOrNull ?: return@forEach
                    val cidx = heroIndexMap[heroName] ?: return@forEach
                    val key = "${prefix}c${cidx}_level"
                    val strVal = level.toString()
                    val existing = nodes[key]
                    if (existing != null) {
                        if (nodeValue(existing) != strVal) {
                            updates[key] = XmlPatchOp(existing.tagName, strVal, existing)
                        }
                    } else if (level > 0) {
                        updates[key] = XmlPatchOp("int", strVal, null)
                    }
                }

                // 4. Hero Skills: <prefix>c_<HeroName>_skill_<skillKey>_unlock
                heroSkillObj?.forEach { (heroName, el) ->
                    val arr = el as? JsonArray ?: return@forEach
                    for (entryEl in arr) {
                        val obj = entryEl as? JsonObject ?: continue
                        val skillKey = (obj["Key"] as? JsonPrimitive)?.intOrNull ?: continue
                        val unlocked = (obj["Value"] as? JsonPrimitive)?.booleanOrNull ?: continue
                        val key = "${prefix}c_${heroName}_skill_${skillKey}_unlock"
                        val strVal = if (unlocked) "1" else "0"
                        val existing = nodes[key]
                        if (existing != null) {
                            if (nodeValue(existing) != strVal) {
                                updates[key] = XmlPatchOp(existing.tagName, strVal, existing)
                            }
                        } else if (unlocked) {
                            updates[key] = XmlPatchOp("int", "1", null)
                        }
                    }
                }

                // 5. Pet Unlocks: <prefix>p<petIdx>_unlock
                petUnlockObj?.forEach { (petName, el) ->
                    val unlocked = (el as? JsonPrimitive)?.booleanOrNull ?: return@forEach
                    val pidx = petIndexMap[petName] ?: return@forEach
                    val key = "${prefix}p${pidx}_unlock"
                    val existing = nodes[key]
                    if (existing != null) {
                        val newVal = formatBoolForExistingNode(existing, unlocked)
                        if (nodeValue(existing) != newVal) {
                            updates[key] = XmlPatchOp(existing.tagName, newVal, existing)
                        }
                    } else if (unlocked) {
                        updates[key] = XmlPatchOp("string", "True", null)
                    }
                }

                // 6. Gems & last_gems: <prefix>gems & <prefix>last_gems
                if (gemsVal != null) {
                    val gemsKey = "${prefix}gems"
                    val gStr = gemsVal.toString()
                    val existingG = nodes[gemsKey]
                    if (existingG != null) {
                        if (nodeValue(existingG) != gStr) {
                            updates[gemsKey] = XmlPatchOp(existingG.tagName, gStr, existingG)
                        }
                    } else {
                        updates[gemsKey] = XmlPatchOp("int", gStr, null)
                    }
                }
                if (lastGemsVal != null) {
                    val lastKey = "${prefix}last_gems"
                    val lgStr = lastGemsVal.toString()
                    val existingLg = nodes[lastKey]
                    if (existingLg != null) {
                        if (nodeValue(existingLg) != lgStr) {
                            updates[lastKey] = XmlPatchOp(existingLg.tagName, lgStr, existingLg)
                        }
                    } else if (nodes.containsKey("${prefix}gems")) {
                        updates[lastKey] = XmlPatchOp("int", lgStr, null)
                    }
                }
                if (gemsVal != null) {
                    // Keep var_gems = 0 and cumulative_gems >= gems for Cloud Save internal consistency
                    val varKey = "${prefix}var_gems"
                    val existingVar = nodes[varKey]
                    if (existingVar != null && nodeValue(existingVar) != "0") {
                        updates[varKey] = XmlPatchOp(existingVar.tagName, "0", existingVar)
                    }

                    val cumKey = "${prefix}cumulative_gems"
                    val existingCum = nodes[cumKey]
                    val existingCumNum = existingCum?.let { nodeValue(it).toLongOrNull() } ?: 0L
                    val targetCum = maxOf(existingCumNum, cumulativeGemsVal ?: 0L, gemsVal)
                    val cumStr = targetCum.toString()
                    if (existingCum != null) {
                        if (nodeValue(existingCum) != cumStr) {
                            updates[cumKey] = XmlPatchOp(existingCum.tagName, cumStr, existingCum)
                        }
                    } else if (targetCum > 0L) {
                        updates[cumKey] = XmlPatchOp("int", cumStr, null)
                    }
                }

                // 7. Fish Chips: <prefix>furniture_fish_chips
                if (fishChipVal != null) {
                    val fcKey = "${prefix}furniture_fish_chips"
                    val fcStr = fishChipVal.toString()
                    val existingFc = nodes[fcKey]
                    if (existingFc != null) {
                        if (nodeValue(existingFc) != fcStr) {
                            updates[fcKey] = XmlPatchOp(existingFc.tagName, fcStr, existingFc)
                        }
                    } else {
                        updates[fcKey] = XmlPatchOp("int", fcStr, null)
                    }
                }

                // 8. Reborn Card: <prefix>reborn_card (1/0)
                if (rebornCardVal != null) {
                    val rcKey = "${prefix}reborn_card"
                    val rcStr = if (rebornCardVal) "1" else "0"
                    val existingRc = nodes[rcKey]
                    if (existingRc != null) {
                        if (nodeValue(existingRc) != rcStr) {
                            updates[rcKey] = XmlPatchOp(existingRc.tagName, rcStr, existingRc)
                        }
                    } else if (rebornCardVal) {
                        updates[rcKey] = XmlPatchOp("int", "1", null)
                    }
                }

                // 9. Room Object Levels: Chest, Safe, Book, Planet, CatFood -> furniture_*
                if (roomObjectLevelObj != null) {
                    val roomKeyMap = mapOf(
                        "Chest" to "furniture_chest",
                        "Safe" to "furniture_safe_level",
                        "Book" to "furniture_book_level",
                        "Planet" to "furniture_plant_level",
                        "CatFood" to "furniture_pet_food_level"
                    )
                    roomKeyMap.forEach { (jsonKey, xmlSuffix) ->
                        val lvl = (roomObjectLevelObj[jsonKey] as? JsonPrimitive)?.intOrNull ?: return@forEach
                        val fullKey = "$prefix$xmlSuffix"
                        val lvlStr = lvl.toString()
                        val existingRoom = nodes[fullKey]
                        if (existingRoom != null) {
                            if (nodeValue(existingRoom) != lvlStr) {
                                updates[fullKey] = XmlPatchOp(existingRoom.tagName, lvlStr, existingRoom)
                            }
                        } else if (lvl > 0) {
                            updates[fullKey] = XmlPatchOp("int", lvlStr, null)
                        }
                    }
                }

                // 10. Mode & Lobby Progression: has_tutorial, first_play, first_in_room, game_times, normal_certificate_0
                if (hasTutorialVal != null && hasTutorialVal >= 1) {
                    val tutKey = "${prefix}has_tutorial"
                    val existingTut = nodes[tutKey]
                    if (existingTut != null) {
                        if ((nodeValue(existingTut).toIntOrNull() ?: 0) < hasTutorialVal) {
                            updates[tutKey] = XmlPatchOp(existingTut.tagName, hasTutorialVal.toString(), existingTut)
                        }
                    } else {
                        updates[tutKey] = XmlPatchOp("int", hasTutorialVal.toString(), null)
                    }

                    val fpKey = "${prefix}first_play"
                    val existingFp = nodes[fpKey]
                    if (existingFp == null) {
                        updates[fpKey] = XmlPatchOp("int", "1", null)
                    } else if ((nodeValue(existingFp).toIntOrNull() ?: 0) < 1) {
                        updates[fpKey] = XmlPatchOp(existingFp.tagName, "1", existingFp)
                    }
                }
                if (firstInRoomVal != null && firstInRoomVal > 0) {
                    val firKey = "${prefix}first_in_room"
                    val existingFir = nodes[firKey]
                    val curFir = existingFir?.let { nodeValue(it).toIntOrNull() } ?: 0
                    if (firstInRoomVal > curFir) {
                        updates[firKey] = XmlPatchOp(existingFir?.tagName ?: "int", firstInRoomVal.toString(), existingFir)
                    }
                }
                if (totalIndexVal != null && totalIndexVal > 0) {
                    val gtKey = "${prefix}game_times"
                    val existingGt = nodes[gtKey]
                    val curGt = existingGt?.let { nodeValue(it).toIntOrNull() } ?: 0
                    if (totalIndexVal > curGt) {
                        updates[gtKey] = XmlPatchOp(existingGt?.tagName ?: "int", totalIndexVal.toString(), existingGt)
                    }
                }
                if (!normalCertsArr.isNullOrEmpty()) {
                    val cert0Key = "${prefix}normal_certificate_0"
                    if (cert0Key !in nodes) {
                        val certJson = """{"gameMode":0,"cellarPassLevel":2,"characterIdx":0,"characterSkinIdx":0,"petIndex":0,"followerId":"","skillIndex":0,"date":"26-11-22","killCount":486,"gainCoin":640,"dealDamage":32450,"get":false,"isBadass":true,"isExtendedLevel":true,"battleFactors":[],"characterBuffs":[],"buffs":[7,9,16,21,38],"additionBuffs":[],"drinks":[],"weaponNames":["weapon_001"],"weaponItems":[""],"weaponAffixes":[],"weaponSkins":[0],"weaponEvolutionStatus":[],"costTime":"00:24:15","magicStoneIdx":0,"version":"","difficulty":3,"weaponAffixCertificateData":[]}"""
                        val encodedCert = URLEncoder.encode(certJson, "UTF-8").replace("+", "%20")
                        updates[cert0Key] = XmlPatchOp("string", encodedCert, null)
                    }
                }
            }

            if (updates.isEmpty()) {
                return@runCatching PlayerPrefsSyncResult(
                    updatedKeysCount = 0,
                    syncedAccounts = targetPrefixes.map { it.trimEnd('_').ifEmpty { "default" } },
                    prefsPath = prefsPath
                )
            }

            val patchedXml = applySurgicalXmlPatches(origXmlText, updates)
            val patchedBytes = patchedXml.toByteArray(Charsets.UTF_8)
            // Validate well-formed XML before writing to disk!
            parsePrefsNodes(patchedBytes)

            val rbFile = rootBackend.writeBytesTransactional(
                path = prefsPath,
                bytes = patchedBytes,
                packageNameToStop = resolvedPkg,
                createRollback = true
            ).getOrThrow()

            PlayerPrefsSyncResult(
                updatedKeysCount = updates.size,
                syncedAccounts = targetPrefixes.map { it.trimEnd('_').ifEmpty { "default" } },
                prefsPath = prefsPath,
                rollbackPath = rbFile?.absolutePath
            )
        }
    }

    /**
     * Safely backs up and moves `*.data.new` / `*.data.rij` out of game `/files/`
     * and sets `OpenRijTest_*` & `OpenNewtonJsonTest_*` = `0` surgically in `playerprefs.xml`.
     */
    suspend fun switchToClassicFormat(
        profile: GameProfile,
        packageName: String,
        currentFilePath: String? = null
    ): Result<ClassicFormatRepairResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedPkg = extractPackageFromPath(currentFilePath) ?: packageName
            val rootInfo = rootProber.probe()
            check(rootInfo.hasRoot) { "Cần quyền Root để đồng bộ định dạng Classic Save" }

            rootBackend.forceStopPackage(resolvedPkg)

            val diag = inspectNewFormatConflict(profile, resolvedPkg, currentFilePath)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupDir = File(storagePaths.getBackupsDir(), "rij_moved_$ts").apply { mkdirs() }

            var moved = 0
            for (remotePath in diag.newFormatFiles) {
                val fileName = File(remotePath).name
                val bytes = rootBackend.readBytes(remotePath).getOrThrow()
                File(backupDir, fileName).writeBytes(bytes)
                rootBackend.deleteFile(remotePath).getOrThrow()
                moved++
            }

            var clearedFlags = 0
            val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, resolvedPkg, profile)
            if (prefsPath != null) {
                val origBytes = rootBackend.readBytes(prefsPath).getOrThrow()
                val origText = origBytes.toString(Charsets.UTF_8)
                val nodes = parsePrefsNodes(origBytes)
                val updates = linkedMapOf<String, XmlPatchOp>()
                val seenUids = mutableSetOf<String>()

                nodes.forEach { (name, el) ->
                    if (name.startsWith("OpenRijTest") || name.startsWith("OpenNewtonJsonTest")) {
                        if (nodeValue(el) != "0") {
                            updates[name] = XmlPatchOp(el.tagName, "0", el)
                            clearedFlags++
                        }
                        val suffix = name.substringAfter('_', "")
                        if (suffix.isNotBlank()) seenUids += suffix
                    }
                }

                for (uid in seenUids) {
                    val firstActiveKey = "${uid}_first_active"
                    if (firstActiveKey !in nodes) {
                        updates[firstActiveKey] = XmlPatchOp("int", "1", null)
                    }
                }

                if (updates.isNotEmpty()) {
                    val patchedBytes = applySurgicalXmlPatches(origText, updates).toByteArray(Charsets.UTF_8)
                    parsePrefsNodes(patchedBytes)
                    rootBackend.writeBytesTransactional(
                        path = prefsPath,
                        bytes = patchedBytes,
                        packageNameToStop = resolvedPkg,
                        createRollback = true
                    ).getOrThrow()
                }
            }

            ClassicFormatRepairResult(
                movedFilesCount = moved,
                backupFolder = backupDir.absolutePath,
                clearedFlagsCount = clearedFlags
            )
        }
    }

    /**
     * Restores previously moved `.data.new` / `.data.rij` files from the latest `rij_moved_*`
     * backup folder and re-enables `OpenRijTest*` / `OpenNewtonJsonTest*` = `1`.
     */
    suspend fun restoreLatestMovedNewFormat(
        profile: GameProfile,
        packageName: String,
        currentFilePath: String? = null
    ): Result<RestoreNewFormatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedPkg = extractPackageFromPath(currentFilePath) ?: packageName
            val rootInfo = rootProber.probe()
            check(rootInfo.hasRoot) { "Cần quyền Root để khôi phục tệp .data.new" }

            val backupDir = findLatestMovedBackupDir()
                ?: error("Không tìm thấy thư mục sao lưu rij_moved_* nào để khôi phục")
            val backupFiles = backupDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".data.new", ignoreCase = true) || f.name.endsWith(".data.rij", ignoreCase = true))
            }?.sortedBy { it.name }.orEmpty()
            check(backupFiles.isNotEmpty()) { "Thư mục sao lưu '${backupDir.name}' không có tệp .data.new nào" }

            rootBackend.forceStopPackage(resolvedPkg)

            val filesDir = if (!currentFilePath.isNullOrBlank() && currentFilePath.contains("/files/")) {
                File(currentFilePath).parent ?: rootBackend.resolveGameFilesDir(rootInfo.activeUserId, resolvedPkg, profile)
            } else {
                rootBackend.resolveGameFilesDir(rootInfo.activeUserId, resolvedPkg, profile)
            }

            var restored = 0
            for (localFile in backupFiles) {
                val targetPath = "$filesDir/${localFile.name}"
                rootBackend.writeBytesTransactional(
                    path = targetPath,
                    bytes = localFile.readBytes(),
                    packageNameToStop = null,
                    createRollback = false
                ).getOrThrow()
                restored++
            }

            var enabledFlags = 0
            val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, resolvedPkg, profile)
            if (prefsPath != null) {
                val origBytes = rootBackend.readBytes(prefsPath).getOrThrow()
                val origText = origBytes.toString(Charsets.UTF_8)
                val nodes = parsePrefsNodes(origBytes)
                val updates = linkedMapOf<String, XmlPatchOp>()

                nodes.forEach { (name, el) ->
                    if ((name.startsWith("OpenRijTest") || name.startsWith("OpenNewtonJsonTest")) &&
                        nodeValue(el) != "1"
                    ) {
                        updates[name] = XmlPatchOp(el.tagName, "1", el)
                        enabledFlags++
                    }
                }

                if (updates.isNotEmpty()) {
                    val patchedBytes = applySurgicalXmlPatches(origText, updates).toByteArray(Charsets.UTF_8)
                    parsePrefsNodes(patchedBytes)
                    rootBackend.writeBytesTransactional(
                        path = prefsPath,
                        bytes = patchedBytes,
                        packageNameToStop = resolvedPkg,
                        createRollback = true
                    ).getOrThrow()
                }
            }

            RestoreNewFormatResult(restoredFilesCount = restored, enabledFlagsCount = enabledFlags)
        }
    }

    suspend fun readPrefsSnapshot(
        profile: GameProfile,
        packageName: String
    ): Pair<String, Map<String, String>>? = withContext(Dispatchers.IO) {
        val rootInfo = rootProber.probe()
        if (!rootInfo.hasRoot || packageName.isBlank()) return@withContext null
        val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, packageName, profile) ?: return@withContext null
        val bytes = rootBackend.readBytes(prefsPath).getOrNull() ?: return@withContext null
        val nodes = runCatching { parsePrefsNodes(bytes) }.getOrNull() ?: return@withContext null
        prefsPath to nodes.mapValues { (_, el) -> nodeValue(el) }
    }

    suspend fun setPrefsIntFlags(
        profile: GameProfile,
        packageName: String,
        flags: Map<String, Int>
    ): Result<PlayerPrefsSyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val rootInfo = rootProber.probe()
            check(rootInfo.hasRoot) { "Cần quyền Root để cập nhật playerprefs.xml" }
            val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, packageName, profile)
                ?: error("Không tìm thấy tệp playerprefs.xml")
            val origBytes = rootBackend.readBytes(prefsPath).getOrThrow()
            val origXml = origBytes.toString(Charsets.UTF_8)
            val nodes = parsePrefsNodes(origBytes)
            val updates = linkedMapOf<String, XmlPatchOp>()

            flags.forEach { (key, value) ->
                val strVal = value.toString()
                val existing = nodes[key]
                if (existing != null) {
                    if (nodeValue(existing) != strVal) {
                        updates[key] = XmlPatchOp(existing.tagName, strVal, existing)
                    }
                } else {
                    updates[key] = XmlPatchOp("int", strVal, null)
                }
            }

            if (updates.isEmpty()) {
                return@runCatching PlayerPrefsSyncResult(0, emptyList(), prefsPath)
            }

            val patchedBytes = applySurgicalXmlPatches(origXml, updates).toByteArray(Charsets.UTF_8)
            parsePrefsNodes(patchedBytes)
            val rb = rootBackend.writeBytesTransactional(
                path = prefsPath,
                bytes = patchedBytes,
                packageNameToStop = packageName,
                createRollback = true
            ).getOrThrow()

            PlayerPrefsSyncResult(
                updatedKeysCount = updates.size,
                syncedAccounts = emptyList(),
                prefsPath = prefsPath,
                rollbackPath = rb?.absolutePath
            )
        }
    }

    /**
     * Clones gameplay-only `<sourceUid>_*` keys into `<targetUid>_*` inside live `playerprefs.xml`
     * (either from live XML or from an external `.skdb` backup XML), strictly excluding all
     * account/session/cloud-id/IAP-receipt keys!
     */
    suspend fun clonePrefsBetweenUids(
        profile: GameProfile,
        packageName: String,
        sourceUid: String,
        targetUid: String,
        preferModern: Boolean,
        sourcePrefsBytesOverride: ByteArray? = null
    ): Result<PlayerPrefsSyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(targetUid.isNotBlank()) { "Target UID không được để trống" }
            val rootInfo = rootProber.probe()
            check(rootInfo.hasRoot) { "Cần quyền Root để chuyển UID trong playerprefs.xml" }

            val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, packageName, profile)
                ?: error("Không tìm thấy tệp playerprefs.xml trong shared_prefs")

            val liveBytes = rootBackend.readBytes(prefsPath).getOrThrow()
            val liveXml = liveBytes.toString(Charsets.UTF_8)
            val liveNodes = parsePrefsNodes(liveBytes)

            val srcNodes = if (sourcePrefsBytesOverride != null) {
                parsePrefsNodes(sourcePrefsBytesOverride)
            } else {
                liveNodes
            }

            val srcPrefix = if (sourceUid.isBlank()) "" else "${sourceUid}_"
            val dstPrefix = "${targetUid}_"
            val updates = linkedMapOf<String, XmlPatchOp>()

            val forbiddenFragments = listOf(
                "cloudsaveid", "sdkusercache", "sdksessioncache", "sdkstatecache",
                "legacyorders", "usedaccounts", "active_shop_task_cache",
                "upload_save_count", "download_save_count", "bp_unclaimed_skin_fix",
                "last_account_id", "last_chilly_uid", "hasmoveddata", "returnplayerdistro",
                "account_enter_game_count", "receipt", "order", "token", "login", "session"
            )

            srcNodes.forEach { (key, srcEl) ->
                val suffix = when {
                    srcPrefix.isNotEmpty() && key.startsWith(srcPrefix, ignoreCase = true) ->
                        key.substring(srcPrefix.length)
                    srcPrefix.isEmpty() && isPlainGameplayKey(key) -> key
                    else -> return@forEach
                }
                val lowerSuffix = suffix.lowercase()
                if (forbiddenFragments.any { lowerSuffix.contains(it) }) return@forEach

                val dstKey = "$dstPrefix$suffix"
                val srcVal = nodeValue(srcEl)
                val existingDst = liveNodes[dstKey]
                if (existingDst != null) {
                    if (nodeValue(existingDst) != srcVal) {
                        updates[dstKey] = XmlPatchOp(existingDst.tagName, srcVal, existingDst)
                    }
                } else {
                    updates[dstKey] = XmlPatchOp(srcEl.tagName, srcVal, null)
                }
            }

            if (preferModern) {
                for (flagKey in listOf("OpenRijTest_$targetUid", "OpenNewtonJsonTest_$targetUid")) {
                    val ex = liveNodes[flagKey]
                    if (ex == null) {
                        updates[flagKey] = XmlPatchOp("int", "1", null)
                    } else if (nodeValue(ex) != "1") {
                        updates[flagKey] = XmlPatchOp(ex.tagName, "1", ex)
                    }
                }
            }

            if (updates.isEmpty()) {
                return@runCatching PlayerPrefsSyncResult(0, listOf(targetUid), prefsPath)
            }

            val patchedBytes = applySurgicalXmlPatches(liveXml, updates).toByteArray(Charsets.UTF_8)
            parsePrefsNodes(patchedBytes)
            val rb = rootBackend.writeBytesTransactional(
                path = prefsPath,
                bytes = patchedBytes,
                packageNameToStop = packageName,
                createRollback = true
            ).getOrThrow()

            PlayerPrefsSyncResult(
                updatedKeysCount = updates.size,
                syncedAccounts = listOf(targetUid),
                prefsPath = prefsPath,
                rollbackPath = rb?.absolutePath
            )
        }
    }

    private fun isPlainGameplayKey(key: String): Boolean =
        key.matches(Regex("^c\\d+_(?:unlock|level|skin\\d+)$")) ||
            key.matches(Regex("^p\\d+_unlock$")) ||
            key.matches(Regex("^c_[A-Za-z0-9]+_skill_\\d+_unlock$")) ||
            key in setOf(
                "gems", "last_gems", "var_gems", "cumulative_gems", "reborn_card",
                "furniture_chest", "furniture_safe_level", "furniture_book_level",
                "furniture_plant_level", "furniture_pet_food_level",
                "has_tutorial", "first_play", "first_in_room", "game_times"
            )

    private data class XmlPatchOp(
        val tagName: String,
        val newValue: String,
        val existingNode: Element?
    )

    private fun parsePrefsNodes(bytes: ByteArray): LinkedHashMap<String, Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val doc = builder.parse(ByteArrayInputStream(bytes))
        val map = linkedMapOf<String, Element>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (name.isNotEmpty()) {
                map[name] = el
            }
        }
        return map
    }

    private fun nodeValue(el: Element): String =
        if (el.hasAttribute("value")) el.getAttribute("value") else el.textContent

    private fun formatBoolForExistingNode(el: Element, value: Boolean): String = when (el.tagName) {
        "int" -> if (value) "1" else "0"
        "boolean" -> if (value) "true" else "false"
        else -> {
            val cur = nodeValue(el).trim()
            if (cur == "true" || cur == "false") {
                if (value) "true" else "false"
            } else {
                if (value) "True" else "False"
            }
        }
    }

    suspend fun inspectPrefsAccounts(
        profile: GameProfile,
        packageName: String
    ): PrefsAccountSummary = withContext(Dispatchers.IO) {
        val rootInfo = rootProber.probe()
        if (!rootInfo.hasRoot || packageName.isBlank()) {
            return@withContext PrefsAccountSummary(null, null, emptyList(), false)
        }
        val prefsPath = resolveActualPrefsPath(rootInfo.activeUserId, packageName, profile)
            ?: return@withContext PrefsAccountSummary(null, null, emptyList(), false)
        val bytes = rootBackend.readBytes(prefsPath).getOrNull()
            ?: return@withContext PrefsAccountSummary(prefsPath, null, emptyList(), false)
        val nodes = runCatching { parsePrefsNodes(bytes) }.getOrDefault(linkedMapOf())
        val observed = linkedSetOf<String>()
        nodes.keys.forEach { key ->
            val m = roleKeyRegex.matchEntire(key) ?: skinKeyRegex.matchEntire(key)
            if (m != null) {
                observed += m.groupValues[1].trimEnd('_')
            }
        }
        val activeHint = detectActiveAccountHint(nodes)
        val rijEnabled = nodes.any { (k, el) ->
            (k.startsWith("OpenRijTest") || k.startsWith("OpenNewtonJsonTest")) && nodeValue(el) == "1"
        }
        PrefsAccountSummary(
            prefsPath = prefsPath,
            activeUidHint = activeHint,
            observedUids = observed.toList(),
            isModernRijEnabled = rijEnabled
        )
    }

    suspend fun resolveActualPrefsPath(
        userId: Int,
        packageName: String,
        profile: GameProfile
    ): String? {
        val primary = rootBackend.resolveSharedPrefsPath(userId, packageName, profile)
        if (rootBackend.exists(primary)) return primary

        val prefsDir = "${rootBackend.resolveGameDataRoot(userId, packageName)}/shared_prefs"
        val entries = rootBackend.listDirectory(prefsDir, profile).getOrDefault(emptyList())
        return entries.firstOrNull { !it.stat.isDirectory && it.name.contains("playerprefs", ignoreCase = true) && it.name.endsWith(".xml", ignoreCase = true) }?.fullPath
            ?: entries.filter { !it.stat.isDirectory && it.name.endsWith(".xml", ignoreCase = true) }
                .maxByOrNull { it.stat.sizeBytes }?.fullPath
    }

    private fun resolvePrefixesForForcedUid(nodes: Map<String, Element>, uid: String): List<String> {
        if (uid.isBlank()) return listOf("")
        val exactMatch = nodes.keys.firstNotNullOfOrNull { key ->
            val m = roleKeyRegex.matchEntire(key) ?: skinKeyRegex.matchEntire(key)
            val rawPrefix = m?.groupValues?.get(1)
            if (rawPrefix != null && rawPrefix.trimEnd('_').equals(uid, ignoreCase = true)) rawPrefix else null
        }
        return listOf(exactMatch ?: "${uid}_")
    }

    private fun resolveTargetPrefixes(nodes: Map<String, Element>): List<String> {
        val observed = linkedSetOf<String>()
        nodes.keys.forEach { key ->
            val m = roleKeyRegex.matchEntire(key) ?: skinKeyRegex.matchEntire(key)
            if (m != null) {
                observed += m.groupValues[1] // exact raw prefix: "", "77614388_", "LOCAL_", "LOCAL"
            }
        }

        val activeHint = detectActiveAccountHint(nodes)
        if (observed.size == 1) {
            return observed.toList()
        }
        if (observed.size > 1) {
            if (!activeHint.isNullOrBlank()) {
                val matching = observed.filter { it.trimEnd('_').equals(activeHint, ignoreCase = true) }
                if (matching.isNotEmpty()) return matching
            }
            val realUids = observed.filter {
                val trimmed = it.trimEnd('_')
                trimmed.isNotEmpty() && !trimmed.equals("LOCAL", ignoreCase = true)
            }
            if (realUids.isNotEmpty()) return realUids
            return observed.toList()
        }
        if (!activeHint.isNullOrBlank()) {
            return listOf("${activeHint}_")
        }
        if (nodes.keys.any { it.startsWith("LOCAL_") }) {
            return listOf("LOCAL_")
        }
        if (nodes.keys.any { it.startsWith("LOCAL") && !it.startsWith("LOCAL_") }) {
            return listOf("LOCAL")
        }
        return listOf("")
    }

    private fun detectActiveAccountHint(nodes: Map<String, Element>): String? {
        nodes["last_account_id"]?.let { el ->
            val v = nodeValue(el).trim()
            if (v.isNotEmpty() && v != "0") return v
        }
        nodes["last_chilly_uid"]?.let { el ->
            val v = nodeValue(el).trim()
            if (v.isNotEmpty() && v != "0") return v
        }
        nodes["cloudSaveId"]?.let { el ->
            val v = nodeValue(el).trim()
            val m = Regex("^(?:TapTap|GPV2|GP|Apple|Chilly)_([A-Za-z0-9]+)$").matchEntire(v)
            if (m != null) return m.groupValues[1]
        }
        nodes.entries.firstOrNull { it.key.contains("SdkUserCache") }?.value?.let { el ->
            runCatching {
                val raw = URLDecoder.decode(nodeValue(el).trim(), "UTF-8")
                if (raw.startsWith("{")) {
                    val obj = json.parseToJsonElement(raw) as? JsonObject
                    val id = (obj?.get("Id") as? JsonPrimitive)?.content?.trim()
                    if (!id.isNullOrEmpty() && id != "0") return id
                }
            }
        }
        nodes.entries.firstOrNull { it.key.contains("SdkStateCache") }?.value?.let { el ->
            runCatching {
                val raw = URLDecoder.decode(nodeValue(el).trim(), "UTF-8")
                if (raw.startsWith("{")) {
                    val obj = json.parseToJsonElement(raw) as? JsonObject
                    val userObj = obj?.get("User") as? JsonObject
                    val id = (userObj?.get("Id") as? JsonPrimitive)?.content?.trim()
                    if (!id.isNullOrEmpty() && id != "0") return id
                }
            }
        }
        return null
    }

    /**
     * Surgically updates existing XML entries in-place without disturbing formatting, quotes,
     * or URL-encoded values of untouched tags, and appends new entries cleanly before `</map>`.
     */
    private fun applySurgicalXmlPatches(
        originalXml: String,
        updates: Map<String, XmlPatchOp>
    ): String {
        var text = originalXml
        val toAppend = StringBuilder()

        for ((key, op) in updates) {
            val existing = op.existingNode
            if (existing != null) {
                val tag = existing.tagName
                val escapedName = Regex.escape(key)
                val pattern = Regex("(?s)<$tag\\b[^>]*\\bname\\s*=\\s*[\"']$escapedName[\"'][^>]*(?:/>|>.*?</$tag\\s*>)")
                val match = pattern.find(text)
                if (match != null) {
                    val token = match.value
                    val updatedToken = if (existing.hasAttribute("value")) {
                        val attrMatch = Regex("\\bvalue\\s*=\\s*([\"'])(.*?)\\1").find(token)
                        if (attrMatch != null) {
                            token.replaceRange(attrMatch.groups[2]!!.range, op.newValue)
                        } else {
                            token
                        }
                    } else {
                        token.substringBefore('>') + ">" + escapeXmlText(op.newValue) + "</$tag>"
                    }
                    text = text.replaceRange(match.range, updatedToken)
                    continue
                }
            }

            if (op.tagName == "string") {
                toAppend.append("    <string name=\"${escapeXmlAttr(key)}\">${escapeXmlText(op.newValue)}</string>\n")
            } else {
                toAppend.append("    <${op.tagName} name=\"${escapeXmlAttr(key)}\" value=\"${escapeXmlAttr(op.newValue)}\" />\n")
            }
        }

        if (toAppend.isNotEmpty()) {
            val closingIdx = text.lastIndexOf("</map>")
            text = if (closingIdx >= 0) {
                text.substring(0, closingIdx) + toAppend.toString() + text.substring(closingIdx)
            } else {
                text + "\n" + toAppend.toString()
            }
        }
        return text
    }

    private fun escapeXmlText(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeXmlAttr(s: String): String = escapeXmlText(s).replace("\"", "&quot;")

    private fun findLatestMovedBackupDir(): File? {
        val backupsDir = storagePaths.getBackupsDir()
        return backupsDir.listFiles { f -> f.isDirectory && f.name.startsWith("rij_moved_") }
            ?.maxByOrNull { it.name }
    }

    private fun extractPackageFromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return Regex("^/data/(?:user/\\d+|data)/([^/]+)/").find(path)?.groupValues?.getOrNull(1)
    }
}
