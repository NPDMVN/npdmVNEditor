package com.dmedit.core.nativebridge

import com.dmedit.core.model.BackupArchiveInfo
import com.dmedit.core.model.BackupManifest
import com.dmedit.core.model.DataFormat
import com.dmedit.core.model.DiffEntry
import com.dmedit.core.model.ExtractedBackupFile
import com.dmedit.core.model.FileRule
import com.dmedit.core.model.GameProfile
import com.dmedit.core.model.SanitizedXmlResult
import com.dmedit.core.model.SanitizerConfig
import com.dmedit.core.model.SsaidInspection
import com.dmedit.core.model.SsaidUpdateMeta
import com.dmedit.core.model.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class SsaidUpdateJniResponse(
    val meta: SsaidUpdateMeta,
    val updatedBytesHex: String
)

@Serializable
private data class CreateBackupStagingRequest(
    val targetPath: String,
    val profile: GameProfile,
    val packageName: String,
    val gameFilesStaging: Map<String, String>,
    val sharedPrefsFileName: String? = null,
    val sharedPrefsStagingPath: String? = null,
    val sanitizeAccountData: Boolean = true,
    val appVersion: String = "1.0.0"
)

data class VerifiedSsaidUpdate(
    val meta: SsaidUpdateMeta,
    val updatedBytes: ByteArray
)

@Singleton
class RustCoreEngine @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun ensureLoaded() {
        check(RustCoreJni.isLoaded) {
            "Thư viện Rust 'libdmedit_core.so' chưa được nạp (${RustCoreJni.loadError}). " +
                "Hãy chạy rust/scripts/build-android-libs.ps1 bằng cargo-ndk trước khi build APK."
        }
    }

    fun coreBanner(): String = if (RustCoreJni.isLoaded) {
        runCatching { RustCoreJni.nativeCoreBanner() }.getOrElse { "Rust Core error: ${it.message}" }
    } else {
        "libdmedit_core.so chưa được nạp (${RustCoreJni.loadError})"
    }

    fun matchFileRule(profile: GameProfile, fileName: String): FileRule? {
        ensureLoaded()
        val pJson = json.encodeToString(GameProfile.serializer(), profile)
        val out = RustCoreJni.nativeMatchFileRule(pJson, fileName)
        return json.decodeFromString(FileRule.serializer().nullable, out)
    }

    fun hasValidCodecKey(profile: GameProfile, codecId: String): Boolean {
        ensureLoaded()
        val pJson = json.encodeToString(GameProfile.serializer(), profile)
        return RustCoreJni.nativeHasValidCodecKey(pJson, codecId)
    }

    fun decryptBytes(profile: GameProfile, codecId: String, input: ByteArray): ByteArray {
        ensureLoaded()
        val pJson = json.encodeToString(GameProfile.serializer(), profile)
        return RustCoreJni.nativeDecryptBytes(pJson, codecId, input)
    }

    fun encryptBytes(
        profile: GameProfile,
        codecId: String,
        plaintext: ByteArray,
        originalRaw: ByteArray? = null
    ): ByteArray {
        ensureLoaded()
        val pJson = json.encodeToString(GameProfile.serializer(), profile)
        return RustCoreJni.nativeEncryptBytes(pJson, codecId, plaintext, originalRaw)
    }

    fun validateContent(content: String, format: DataFormat): ValidationResult {
        ensureLoaded()
        val raw = RustCoreJni.nativeValidateContent(content, format.name.lowercase())
        return json.decodeFromString(ValidationResult.serializer(), raw)
    }

    fun beautifyJson(content: String): Result<String> = runCatching {
        ensureLoaded()
        RustCoreJni.nativeBeautifyJson(content)
    }

    fun minifyJson(content: String): Result<String> = runCatching {
        ensureLoaded()
        RustCoreJni.nativeMinifyJson(content)
    }

    fun patchJsonPath(jsonText: String, jsonPath: String, newValue: JsonElement): String {
        ensureLoaded()
        val valJson = json.encodeToString(JsonElement.serializer(), newValue)
        return RustCoreJni.nativePatchJsonPath(jsonText, jsonPath, valJson)
    }

    fun patchMapEntry(
        jsonText: String,
        mapPath: String,
        entryKey: String,
        newValue: JsonElement
    ): String {
        ensureLoaded()
        val valJson = json.encodeToString(JsonElement.serializer(), newValue)
        return RustCoreJni.nativePatchMapEntry(jsonText, mapPath, entryKey, valJson)
    }

    fun batchSetMap(jsonText: String, mapPath: String, newValue: JsonElement): String {
        ensureLoaded()
        val valJson = json.encodeToString(JsonElement.serializer(), newValue)
        return RustCoreJni.nativeBatchSetMap(jsonText, mapPath, valJson)
    }

    fun batchUpdateNestedPairs(
        jsonText: String,
        mapPath: String,
        groupKey: String?,
        valueField: String,
        newValue: JsonElement
    ): String {
        ensureLoaded()
        val valJson = json.encodeToString(JsonElement.serializer(), newValue)
        return RustCoreJni.nativeBatchUpdateNestedPairs(jsonText, mapPath, groupKey, valueField, valJson)
    }

    fun mergeJsonPreset(
        jsonText: String,
        targetPath: String,
        presetElement: JsonElement,
        overwriteExisting: Boolean,
        dedupeKey: String? = null
    ): String {
        ensureLoaded()
        val presetJson = json.encodeToString(JsonElement.serializer(), presetElement)
        return RustCoreJni.nativeMergeJsonPreset(
            jsonText = jsonText,
            targetPath = targetPath,
            presetJson = presetJson,
            overwriteExisting = overwriteExisting,
            dedupeKey = dedupeKey
        )
    }

    fun diffTexts(originalText: String, modifiedText: String): List<DiffEntry> {
        ensureLoaded()
        val raw = RustCoreJni.nativeDiffTexts(originalText, modifiedText)
        return json.decodeFromString(ListSerializer(DiffEntry.serializer()), raw)
    }

    fun findAccountKeys(xmlText: String, config: SanitizerConfig): List<String> {
        ensureLoaded()
        val cfgJson = json.encodeToString(SanitizerConfig.serializer(), config)
        val raw = RustCoreJni.nativeFindAccountKeys(xmlText, cfgJson)
        return json.decodeFromString(ListSerializer(String.serializer()), raw)
    }

    fun sanitizePlayerPrefs(xmlText: String, config: SanitizerConfig): SanitizedXmlResult {
        ensureLoaded()
        val cfgJson = json.encodeToString(SanitizerConfig.serializer(), config)
        val raw = RustCoreJni.nativeSanitizePlayerPrefs(xmlText, cfgJson)
        return json.decodeFromString(SanitizedXmlResult.serializer(), raw)
    }

    fun generateRandomSsaid(): String {
        ensureLoaded()
        return RustCoreJni.nativeGenerateRandomSsaid()
    }

    fun inspectSsaid(ssaidBytes: ByteArray, packageName: String): SsaidInspection {
        ensureLoaded()
        val raw = RustCoreJni.nativeInspectSsaid(ssaidBytes, packageName)
        return json.decodeFromString(SsaidInspection.serializer(), raw)
    }

    fun updateSsaidVerified(
        originalBytes: ByteArray,
        packageName: String,
        newSsaid: String
    ): VerifiedSsaidUpdate {
        ensureLoaded()
        val raw = RustCoreJni.nativeUpdateSsaidVerified(originalBytes, packageName, newSsaid)
        val parsed = json.decodeFromString(SsaidUpdateJniResponse.serializer(), raw)
        return VerifiedSsaidUpdate(
            meta = parsed.meta,
            updatedBytes = hexToBytes(parsed.updatedBytesHex)
        )
    }

    fun createBackupFromStaging(
        targetFile: File,
        profile: GameProfile,
        packageName: String,
        stagedGameFiles: Map<String, File>,
        sharedPrefsFileName: String? = null,
        stagedSharedPrefsFile: File? = null,
        sanitizeAccountData: Boolean = true,
        appVersion: String = "1.0.0"
    ): BackupManifest {
        ensureLoaded()
        val req = CreateBackupStagingRequest(
            targetPath = targetFile.absolutePath,
            profile = profile,
            packageName = packageName,
            gameFilesStaging = stagedGameFiles.mapValues { it.value.absolutePath },
            sharedPrefsFileName = sharedPrefsFileName,
            sharedPrefsStagingPath = stagedSharedPrefsFile?.absolutePath,
            sanitizeAccountData = sanitizeAccountData,
            appVersion = appVersion
        )
        val raw = RustCoreJni.nativeCreateBackupFromStaging(
            json.encodeToString(CreateBackupStagingRequest.serializer(), req)
        )
        return json.decodeFromString(BackupManifest.serializer(), raw)
    }

    fun inspectBackup(archiveFile: File): BackupArchiveInfo {
        ensureLoaded()
        val raw = RustCoreJni.nativeInspectBackup(archiveFile.absolutePath)
        return json.decodeFromString(BackupArchiveInfo.serializer(), raw)
            .copy(lastModifiedMs = archiveFile.lastModified())
    }

    fun verifyAndReadEntry(archiveFile: File, entryArchivePath: String): ByteArray {
        ensureLoaded()
        return RustCoreJni.nativeVerifyAndReadEntry(archiveFile.absolutePath, entryArchivePath)
    }

    fun verifyAndExtractToStaging(
        archiveFile: File,
        selectedPaths: Set<String>?,
        stagingDir: File
    ): List<ExtractedBackupFile> {
        ensureLoaded()
        val selJson = if (selectedPaths == null) {
            ""
        } else {
            json.encodeToString(ListSerializer(String.serializer()), selectedPaths.toList())
        }
        val raw = RustCoreJni.nativeVerifyAndExtractToStaging(
            archiveFile.absolutePath,
            selJson,
            stagingDir.absolutePath
        )
        return json.decodeFromString(ListSerializer(ExtractedBackupFile.serializer()), raw)
    }

    fun updateBackupEntry(
        archiveFile: File,
        entryArchivePath: String,
        newBytes: ByteArray
    ): BackupManifest? {
        ensureLoaded()
        val raw = RustCoreJni.nativeUpdateBackupEntry(
            archiveFile.absolutePath,
            entryArchivePath,
            newBytes
        )
        return json.decodeFromString(BackupManifest.serializer().nullable, raw)
    }

    companion object {
        const val SKDB_EXTENSION = ".skdb"

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}

/**
 * Lightweight read-only JSON path helper used by Compose Visual Form rendering.
 * All writes, lossless patching, validation, and diffing are executed in Rust (`RustCoreEngine`).
 */
object JsonTreeReader {
    fun read(root: JsonElement, path: String): JsonElement? {
        val cleaned = path.trim().removePrefix("$").removePrefix(".")
        if (cleaned.isEmpty()) return root
        var cur: JsonElement = root
        var i = 0
        while (i < cleaned.length) {
            when (cleaned[i]) {
                '.' -> i++
                '[' -> {
                    val end = cleaned.indexOf(']', i + 1)
                    if (end <= i) return null
                    val tok = cleaned.substring(i + 1, end).trim()
                    cur = if ((tok.startsWith("\"") && tok.endsWith("\"")) ||
                        (tok.startsWith("'") && tok.endsWith("'"))
                    ) {
                        (cur as? JsonObject)?.get(tok.substring(1, tok.length - 1)) ?: return null
                    } else {
                        val idx = tok.toIntOrNull() ?: return null
                        (cur as? JsonArray)?.getOrNull(idx) ?: return null
                    }
                    i = end + 1
                }
                else -> {
                    val start = i
                    while (i < cleaned.length && cleaned[i] != '.' && cleaned[i] != '[') i++
                    cur = (cur as? JsonObject)?.get(cleaned.substring(start, i)) ?: return null
                }
            }
        }
        return cur
    }

    fun readObjectMap(root: JsonElement, path: String): Map<String, JsonElement> {
        val target = if (path == "$" || path.isBlank()) root else read(root, path)
        return (target as? JsonObject)?.toMap() ?: emptyMap()
    }

    fun asLong(element: JsonElement?): Long? {
        val prim = element as? JsonPrimitive ?: return null
        return prim.content.toLongOrNull() ?: prim.content.toDoubleOrNull()?.toLong()
    }

    fun asBoolean(element: JsonElement?, trueInt: Int? = null): Boolean? {
        val prim = element as? JsonPrimitive ?: return null
        if (trueInt != null) {
            prim.content.toIntOrNull()?.let { return it == trueInt }
        }
        return when (prim.content.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
}
