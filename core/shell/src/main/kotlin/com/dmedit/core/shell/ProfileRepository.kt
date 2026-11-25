package com.dmedit.core.shell

import android.content.Context
import com.dmedit.core.model.GameProfile
import com.dmedit.core.model.ItemCatalog
import com.dmedit.core.model.VisualSchema
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class PresetOverrideInfo(
    val hasOverride: Boolean = false,
    val presetGroupsCount: Int = 0,
    val totalEntriesCount: Int = 0,
    val updatedAtEpochMs: Long = 0L,
    val filePath: String = ""
)

data class LoadedProfileBundle(
    val profile: GameProfile,
    val sourceDir: String,
    val isExternal: Boolean
)

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val externalRoot: File
        get() = File(context.filesDir, "external_profiles").apply { mkdirs() }

    private val _profiles = MutableStateFlow<List<LoadedProfileBundle>>(emptyList())
    val profiles: StateFlow<List<LoadedProfileBundle>> = _profiles.asStateFlow()

    suspend fun loadAllProfiles(): List<LoadedProfileBundle> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, LoadedProfileBundle>()

        // 1. Load built-in profiles from assets/profiles/
        val assetFolders = runCatching { context.assets.list("profiles")?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        for (folder in assetFolders) {
            runCatching {
                val raw = context.assets.open("profiles/$folder/profile.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val profile = json.decodeFromString(GameProfile.serializer(), raw)
                map[profile.id] = LoadedProfileBundle(
                    profile = profile,
                    sourceDir = "profiles/$folder",
                    isExternal = false
                )
            }
        }

        // 2. Load user-imported external profiles (can override built-in profiles when game updates!)
        externalRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val profileFile = File(dir, "profile.json")
            if (profileFile.exists()) {
                runCatching {
                    val profile = json.decodeFromString(GameProfile.serializer(), profileFile.readText(Charsets.UTF_8))
                    map[profile.id] = LoadedProfileBundle(
                        profile = profile,
                        sourceDir = dir.absolutePath,
                        isExternal = true
                    )
                }
            }
        }

        val list = map.values.toList()
        _profiles.value = list
        list
    }

    suspend fun loadVisualSchema(bundle: LoadedProfileBundle, relativePath: String): VisualSchema? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = readRelativeText(bundle, relativePath) ?: return@runCatching null
                json.decodeFromString(VisualSchema.serializer(), raw)
            }.getOrNull()
        }

    suspend fun loadItemCatalog(bundle: LoadedProfileBundle, relativePath: String): ItemCatalog? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = readRelativeText(bundle, relativePath) ?: return@runCatching null
                json.decodeFromString(ItemCatalog.serializer(), raw)
            }.getOrNull()
        }

    suspend fun loadPresetPack(
        bundle: LoadedProfileBundle,
        relativePath: String
    ): Map<String, JsonElement> = withContext(Dispatchers.IO) {
        val base = runCatching {
            val raw = readRelativeText(bundle, relativePath) ?: return@runCatching emptyMap()
            (json.parseToJsonElement(raw) as? JsonObject)?.toMap() ?: emptyMap()
        }.getOrDefault(emptyMap())

        val overrideFile = presetOverrideFile(bundle.profile.id)
        if (!overrideFile.exists()) return@withContext base

        val overrideMap = runCatching {
            (json.parseToJsonElement(overrideFile.readText(Charsets.UTF_8)) as? JsonObject)?.toMap()
        }.getOrNull() ?: return@withContext base

        mergePresetMaps(base, overrideMap)
    }

    fun presetOverrideFile(profileId: String): File {
        val safeId = profileId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(context.filesDir, "preset_overrides").apply { mkdirs() }
        return File(dir, "${safeId}_preset_override.json")
    }

    suspend fun getPresetOverrideInfo(profileId: String): PresetOverrideInfo = withContext(Dispatchers.IO) {
        val f = presetOverrideFile(profileId)
        if (!f.exists()) return@withContext PresetOverrideInfo(hasOverride = false, filePath = f.absolutePath)
        val map = runCatching {
            (json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject)?.toMap()
        }.getOrNull().orEmpty()
        val totalEntries = map.values.sumOf { el ->
            when (el) {
                is JsonObject -> el.size
                is JsonArray -> el.size
                else -> 1
            }
        }
        PresetOverrideInfo(
            hasOverride = map.isNotEmpty(),
            presetGroupsCount = map.size,
            totalEntriesCount = totalEntries,
            updatedAtEpochMs = f.lastModified(),
            filePath = f.absolutePath
        )
    }

    suspend fun importPresetOverrideJson(profileId: String, rawJson: String): Result<PresetOverrideInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = json.parseToJsonElement(rawJson) as? JsonObject
                    ?: error("Tệp Preset JSON phải có cấu trúc JSON object gốc { ... }")
                require(parsed.isNotEmpty()) { "Tệp Preset JSON rỗng" }
                val existing = runCatching {
                    val f = presetOverrideFile(profileId)
                    if (f.exists()) (json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject)?.toMap() else null
                }.getOrNull().orEmpty()
                val merged = mergePresetMaps(existing, parsed.toMap())
                val f = presetOverrideFile(profileId)
                f.writeText(JsonObject(merged).toString(), Charsets.UTF_8)
                getPresetOverrideInfo(profileId)
            }
        }

    suspend fun savePresetOverrideMap(
        profileId: String,
        newEntries: Map<String, JsonElement>
    ): Result<PresetOverrideInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = runCatching {
                val f = presetOverrideFile(profileId)
                if (f.exists()) (json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject)?.toMap() else null
            }.getOrNull().orEmpty()
            val merged = mergePresetMaps(existing, newEntries)
            val f = presetOverrideFile(profileId)
            f.writeText(JsonObject(merged).toString(), Charsets.UTF_8)
            getPresetOverrideInfo(profileId)
        }
    }

    suspend fun clearPresetOverride(profileId: String): Boolean = withContext(Dispatchers.IO) {
        val f = presetOverrideFile(profileId)
        if (f.exists()) f.delete() else false
    }

    private fun mergePresetMaps(
        base: Map<String, JsonElement>,
        extra: Map<String, JsonElement>
    ): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((key, extraVal) in extra) {
            val curVal = out[key]
            out[key] = when {
                curVal is JsonObject && extraVal is JsonObject -> {
                    val mergedObj = LinkedHashMap<String, JsonElement>(curVal)
                    for ((k2, v2) in extraVal) {
                        if (!mergedObj.containsKey(k2)) mergedObj[k2] = v2
                    }
                    JsonObject(mergedObj)
                }
                curVal is JsonArray && extraVal is JsonArray -> {
                    val mergedList = curVal.toMutableList()
                    for (item in extraVal) {
                        if (item !in mergedList) mergedList += item
                    }
                    JsonArray(mergedList)
                }
                else -> extraVal
            }
        }
        return out
    }

    suspend fun importProfileJson(rawJson: String): Result<GameProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = json.decodeFromString(GameProfile.serializer(), rawJson)
            val safeId = profile.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetDir = File(externalRoot, safeId).apply { mkdirs() }
            File(targetDir, "profile.json").writeText(rawJson, Charsets.UTF_8)
            loadAllProfiles()
            profile
        }
    }

    suspend fun importProfileZip(inputStream: InputStream): Result<GameProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(context.cacheDir, "profile_import_${System.nanoTime()}").apply { mkdirs() }
            try {
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val clean = entry.name.replace('\\', '/').trimStart('/')
                            require(!clean.contains("..")) { "Unsafe entry path in profile zip: $clean" }
                            val dest = File(tempDir, clean)
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                val profileJsonFile = File(tempDir, "profile.json")
                require(profileJsonFile.exists()) { "Gói profile thiếu tệp profile.json ở thư mục gốc" }
                val profile = json.decodeFromString(
                    GameProfile.serializer(),
                    profileJsonFile.readText(Charsets.UTF_8)
                )
                val safeId = profile.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val finalDir = File(externalRoot, safeId)
                if (finalDir.exists()) finalDir.deleteRecursively()
                tempDir.copyRecursively(finalDir, overwrite = true)
                loadAllProfiles()
                profile
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun readRelativeText(bundle: LoadedProfileBundle, relativePath: String): String? {
        val clean = relativePath.trimStart('/')
        return if (bundle.isExternal) {
            val f = File(bundle.sourceDir, clean)
            if (f.exists()) f.readText(Charsets.UTF_8) else null
        } else {
            context.assets.open("${bundle.sourceDir}/$clean")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
