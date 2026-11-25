package com.dmedit.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dmedit.app.ui.theme.DmeditTheme

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logText = intent.getStringExtra(EXTRA_CRASH_LOG).orEmpty()
        val logPath = intent.getStringExtra(EXTRA_LOG_PATH).orEmpty()

        setContent {
            DmeditTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("dmedit gặp sự cố ngoài ý muốn!", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                        Text("Đường dẫn nhật ký: $logPath", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("dmedit_crash_log", logText))
                            }) { Text("Sao chép Log") }
                            OutlinedButton(onClick = {
                                startActivity(Intent(this@CrashActivity, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                })
                                finish()
                            }) { Text("Khởi động lại") }
                        }
                        Surface(
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            Text(
                                text = logText,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CRASH_LOG = "extra_crash_log"
        const val EXTRA_LOG_PATH = "extra_log_path"
    }
}
