package com.dmedit.core.shell

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStoragePaths @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getBackupsDir(): File {
        val publicDir = File(Environment.getExternalStorageDirectory(), "dmedit/backups")
        return if (runCatching { publicDir.mkdirs(); publicDir.canWrite() }.getOrDefault(false)) {
            publicDir
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }
        }
    }

    fun getLogsDir(): File {
        val publicDir = File(Environment.getExternalStorageDirectory(), "dmedit/Logs")
        return if (runCatching { publicDir.mkdirs(); publicDir.canWrite() }.getOrDefault(false)) {
            publicDir
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "Logs").apply { mkdirs() }
        }
    }

    fun getLatestLogFile(): File = File(getLogsDir(), "latest.log")

    fun getSsaidBackupFile(userId: Int): File =
        File(context.filesDir, "ssaid_backups/user_${userId}_settings_ssaid.xml.orig").apply {
            parentFile?.mkdirs()
        }

    fun getQuickRollbackDir(): File =
        File(context.filesDir, "quick_rollbacks").apply { mkdirs() }

    fun getPresetOverridesDir(): File =
        File(context.filesDir, "preset_overrides").apply { mkdirs() }
}
