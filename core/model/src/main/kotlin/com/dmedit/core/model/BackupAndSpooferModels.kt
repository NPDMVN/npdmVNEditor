package com.dmedit.core.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val schemaVersion: Int = 2,
    val appVersion: String = "1.0.0",
    val gameProfileId: String,
    val packageName: String,
    val createdAtEpochMs: Long,
    val sanitized: Boolean = true,
    val entries: List<BackupEntry> = emptyList()
)

@Serializable
data class BackupEntry(
    val archivePath: String,
    val targetRelativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val isSharedPrefs: Boolean = false
)

@Serializable
data class BackupArchiveInfo(
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long = 0L,
    val manifest: BackupManifest? = null,
    val isLegacySkdb: Boolean = false,
    val entryNames: List<String> = emptyList()
)

@Serializable
enum class SsaidFormat {
    ABX_BINARY,
    PLAIN_XML,
    LEGACY_SECURE_XML,
    UNKNOWN
}

@Serializable
data class SsaidEntry(
    val id: String,
    val name: String,
    val value: String,
    val packageName: String,
    val defaultValue: String? = null,
    val defaultSysSet: Boolean = false,
    val tag: String? = null
)

data class SpooferState(
    val packageName: String = "",
    val accountKeysFound: List<String> = emptyList(),
    val currentSsaid: String? = null,
    val ssaidFormat: SsaidFormat = SsaidFormat.UNKNOWN,
    val ssaidFilePath: String = "",
    val hasOriginalSsaidBackup: Boolean = false,
    val isEmulator: Boolean = false,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)
