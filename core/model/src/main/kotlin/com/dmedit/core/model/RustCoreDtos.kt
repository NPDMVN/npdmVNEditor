package com.dmedit.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

@Serializable
enum class ChangeType {
    ADDED,
    MODIFIED,
    REMOVED
}

@Serializable
data class DiffEntry(
    val path: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val changeType: ChangeType
)

@Serializable
data class SanitizedXmlResult(
    val xml: String,
    val removedKeys: List<String> = emptyList()
)

@Serializable
data class SsaidInspection(
    val format: SsaidFormat,
    val entry: SsaidEntry? = null,
    val totalEntries: Int = 0
)

@Serializable
data class SsaidUpdateMeta(
    val format: SsaidFormat,
    val previousValue: String,
    val newValue: String,
    val verifiedEntryCount: Int
)

@Serializable
data class ExtractedBackupFile(
    val archivePath: String,
    val targetRelativePath: String,
    val isSharedPrefs: Boolean,
    val extractedDiskPath: String
)
