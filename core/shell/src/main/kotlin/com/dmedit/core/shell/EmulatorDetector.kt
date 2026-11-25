package com.dmedit.core.shell

import android.os.Build
import com.dmedit.core.model.EmulatorType
import java.io.File

object EmulatorDetector {

    fun detectEmulator(): EmulatorType {
        val fingerprint = listOf(
            Build.BRAND,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.PRODUCT,
            Build.HARDWARE,
            Build.BOARD,
            Build.FINGERPRINT
        ).joinToString(" ").lowercase()

        return when {
            File("/system/bin/ldinit").exists() ||
                File("/dev/ld_guest").exists() ||
                fingerprint.contains("ldplayer") ||
                fingerprint.contains("changzhi") -> EmulatorType.LDPLAYER

            fingerprint.contains("mumu") ||
                fingerprint.contains("netease") ||
                File("/sdcard/MuMuSharedFolder").exists() -> EmulatorType.MUMU

            fingerprint.contains("bluestacks") ||
                File("/system/bin/bstfolderd").exists() -> EmulatorType.BLUESTACKS

            fingerprint.contains("nox") ||
                File("/system/bin/nox-prop").exists() -> EmulatorType.NOX

            fingerprint.contains("memu") ||
                File("/system/bin/memud").exists() -> EmulatorType.MEMU

            fingerprint.contains("ranchu") ||
                fingerprint.contains("goldfish") ||
                fingerprint.contains("sdk_gphone") ||
                fingerprint.contains("generic") -> EmulatorType.ANDROID_AVD

            else -> EmulatorType.PHYSICAL_DEVICE
        }
    }

    fun detectSharedFolder(emulatorType: EmulatorType): String? {
        val candidates = when (emulatorType) {
            EmulatorType.LDPLAYER -> listOf(
                "/sdcard/Pictures",
                "/storage/emulated/0/Pictures",
                "/mnt/shared/Pictures",
                "/sdcard/Download"
            )
            EmulatorType.MUMU -> listOf(
                "/sdcard/MuMuSharedFolder",
                "/sdcard/Download"
            )
            EmulatorType.BLUESTACKS -> listOf(
                "/sdcard/DCIM/SharedFolder",
                "/sdcard/Windows/BstSharedFolder",
                "/sdcard/Download"
            )
            else -> listOf("/sdcard/Download", "/sdcard/dmedit/backups")
        }
        return candidates.firstOrNull { File(it).exists() } ?: "/sdcard/Download"
    }
}
