package com.dmedit.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class GlobalCrashHandler(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val report = buildCrashReport(thread, throwable)
        val logPath = writeCrashLog(report)

        runCatching {
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_CRASH_LOG, report)
                putExtra(CrashActivity.EXTRA_LOG_PATH, logPath)
            }
            context.startActivity(intent)
        }.onFailure {
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("============================")
            appendLine("dmedit Crash Report ($ts)")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Architecture: ${System.getProperty("os.arch")} | ABIs=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread: ${thread.name}")
            appendLine("============================")
            appendLine(sw.toString())
        }
    }

    private fun writeCrashLog(content: String): String {
        val publicDir = File(Environment.getExternalStorageDirectory(), "dmedit/Logs")
        val targetDir = if (runCatching { publicDir.mkdirs(); publicDir.canWrite() }.getOrDefault(false)) {
            publicDir
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "Logs").apply { mkdirs() }
        }
        val logFile = File(targetDir, "latest.log")
        runCatching { logFile.writeText(content, Charsets.UTF_8) }
        return logFile.absolutePath
    }
}
