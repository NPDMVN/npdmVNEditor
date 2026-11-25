package com.dmedit.core.nativebridge

/**
 * Direct JNI entrypoints into `libdmedit_core.so` compiled from `rust/crates/dmedit-jni`.
 */
object RustCoreJni {
    @Volatile
    var isLoaded: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("dmedit_core")
            isLoaded = true
        } catch (t: Throwable) {
            isLoaded = false
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    external fun nativeCoreBanner(): String
    external fun nativeMatchFileRule(profileJson: String, fileName: String): String
    external fun nativeHasValidCodecKey(profileJson: String, codecId: String): Boolean
    external fun nativeDecryptBytes(profileJson: String, codecId: String, input: ByteArray): ByteArray
    external fun nativeEncryptBytes(
        profileJson: String,
        codecId: String,
        plaintext: ByteArray,
        originalRaw: ByteArray?
    ): ByteArray

    external fun nativeValidateContent(content: String, formatName: String): String
    external fun nativeBeautifyJson(content: String): String
    external fun nativeMinifyJson(content: String): String

    external fun nativePatchJsonPath(jsonText: String, jsonPath: String, newValueJson: String): String
    external fun nativePatchMapEntry(
        jsonText: String,
        mapPath: String,
        entryKey: String,
        newValueJson: String
    ): String
    external fun nativeBatchSetMap(jsonText: String, mapPath: String, newValueJson: String): String
    external fun nativeBatchUpdateNestedPairs(
        jsonText: String,
        mapPath: String,
        groupKey: String?,
        valueField: String,
        newValueJson: String
    ): String
    external fun nativeMergeJsonPreset(
        jsonText: String,
        targetPath: String,
        presetJson: String,
        overwriteExisting: Boolean,
        dedupeKey: String?
    ): String
    external fun nativeDiffTexts(originalText: String, modifiedText: String): String

    external fun nativeFindAccountKeys(xmlText: String, sanitizerJson: String): String
    external fun nativeSanitizePlayerPrefs(xmlText: String, sanitizerJson: String): String

    external fun nativeGenerateRandomSsaid(): String
    external fun nativeInspectSsaid(ssaidBytes: ByteArray, packageName: String): String
    external fun nativeUpdateSsaidVerified(
        originalBytes: ByteArray,
        packageName: String,
        newSsaid: String
    ): String

    external fun nativeCreateBackupFromStaging(requestJson: String): String
    external fun nativeInspectBackup(archivePath: String): String
    external fun nativeVerifyAndReadEntry(archivePath: String, entryArchivePath: String): ByteArray
    external fun nativeVerifyAndExtractToStaging(
        archivePath: String,
        selectedPathsJson: String,
        stagingDir: String
    ): String
    external fun nativeUpdateBackupEntry(
        archivePath: String,
        entryArchivePath: String,
        newBytes: ByteArray
    ): String
}
