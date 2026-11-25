package com.dmedit.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FileStat(
    val modeOctal: String = "660",
    val uid: Int = 0,
    val gid: Int = 0,
    val selinuxContext: String? = null,
    val sizeBytes: Long = 0L,
    val isDirectory: Boolean = false,
    val isSymlink: Boolean = false
)

@Serializable
data class SaveFileEntry(
    val name: String,
    val fullPath: String,
    val relativePath: String,
    val stat: FileStat = FileStat(),
    val matchedRule: FileRule? = null,
    val isGameRelevant: Boolean = false
) {
    val codecId: String get() = matchedRule?.codec ?: "plain"
    val format: DataFormat get() = matchedRule?.format ?: DataFormat.TEXT
    val visualSchemaPath: String? get() = matchedRule?.visualSchema
}

enum class SuVariant {
    MAGISK_MOUNT_MASTER,
    STANDARD_EMULATOR_SU,
    CUSTOM_BINARY,
    UNAVAILABLE
}

enum class EmulatorType(val displayName: String) {
    LDPLAYER("LDPlayer"),
    MUMU("MuMu Player"),
    BLUESTACKS("BlueStacks"),
    NOX("NoxPlayer"),
    MEMU("MEmu"),
    ANDROID_AVD("Android Emulator (AVD)"),
    PHYSICAL_DEVICE("Thiết bị Android thật")
}

@Serializable
enum class StorageMode {
    ROOT,
    SAF_LOCAL,
    ARCHIVE_VIRTUAL
}

data class RootEnvironmentInfo(
    val hasRoot: Boolean = false,
    val suVariant: SuVariant = SuVariant.UNAVAILABLE,
    val kernelArch: String = "unknown",
    val toyboxPath: String? = null,
    val toyboxSource: String = "none",
    val emulatorType: EmulatorType = EmulatorType.PHYSICAL_DEVICE,
    val androidApi: Int = 0,
    val androidRelease: String = "",
    val activeUserId: Int = 0,
    val sharedFolderPath: String? = null,
    val diagnosticMessage: String = ""
)

@Serializable
data class AppSettings(
    val activeProfileId: String = "chillyroom.soul_knight",
    val activePackageOverride: String? = null,
    val filterGameFiles: Boolean = true,
    val forceStopGameBeforeWrite: Boolean = true,
    val autoBackupBeforeSave: Boolean = true,
    val showDiffBeforeSave: Boolean = true,
    val useCustomToybox: Boolean = false,
    val customToyboxCommand: String = "",
    val storageMode: StorageMode = StorageMode.ROOT,
    val darkThemeMode: String = "system",
    val language: String = "vi"
)
