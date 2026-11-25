package com.dmedit.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GameProfile(
    val schemaVersion: Int = 2,
    val id: String,
    val displayName: String,
    val developer: String = "",
    val engine: String = "unity",
    val packages: List<String> = emptyList(),
    val paths: GamePaths = GamePaths(),
    val codecs: Map<String, CodecDefinition> = emptyMap(),
    val fileRules: List<FileRule> = emptyList(),
    val sanitizer: SanitizerConfig = SanitizerConfig(),
    val features: ProfileFeatures = ProfileFeatures(),
    val isGeneric: Boolean = false
)

@Serializable
data class GamePaths(
    val filesDir: String = "files",
    val sharedPrefs: String = "shared_prefs/{package}.v2.playerprefs.xml"
) {
    fun resolveSharedPrefs(packageName: String): String =
        sharedPrefs.replace("{package}", packageName)
}

@Serializable
enum class CodecType {
    @SerialName("plain") PLAIN,
    @SerialName("xor") XOR,
    @SerialName("des_cbc_pkcs5") DES_CBC_PKCS5,
    @SerialName("aes_cbc_pkcs5") AES_CBC_PKCS5,
    @SerialName("aes_cbc_zero_bom") AES_CBC_ZERO_BOM,
    @SerialName("base64") BASE64,
    @SerialName("gzip") GZIP,
    @SerialName("chain") CHAIN
}

@Serializable
data class CodecDefinition(
    val type: CodecType,
    val keyHex: String? = null,
    val keyUtf8: String? = null,
    val ivHex: String? = null,
    val ivUtf8: String? = null,
    val headerSkipBytes: Int = 0,
    val steps: List<String> = emptyList(),
    val description: String = ""
)

@Serializable
enum class DataFormat {
    @SerialName("json") JSON,
    @SerialName("xml") XML,
    @SerialName("text") TEXT,
    @SerialName("binary") BINARY
}

@Serializable
data class FileRule(
    val match: String,
    val codec: String = "plain",
    val format: DataFormat = DataFormat.JSON,
    val visualSchema: String? = null,
    val label: String? = null
)

@Serializable
data class SanitizerConfig(
    val playerPrefsRemovePatterns: List<String> = emptyList(),
    val jsonStripKeys: List<String> = emptyList(),
    val accountSuffixRegex: String? = null
)

@Serializable
data class ProfileFeatures(
    val accountClear: Boolean = true,
    val ssaidSpoof: Boolean = true,
    val forceStopBeforeWrite: Boolean = true
)
