package com.dmedit.feature.spoofer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.bi

@Composable
fun SpooferScreen(
    viewModel: SpooferViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lang by AppLocaleHolder.language.collectAsStateWithLifecycle()
    var customSsaid by remember { mutableStateOf("") }
    var confirmRebootAction by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Spoofer — Account & Android ID (SSAID)", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = bi(
                        "Gói mục tiêu: ${state.packageName.ifBlank { "Chưa xác định" }}",
                        "Target Package: ${state.packageName.ifBlank { "Unknown" }}",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Default.Refresh, contentDescription = bi("Tải lại", "Refresh", lang))
            }
        }

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.errorMessage?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
        state.statusMessage?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(12.dp))
            }
        }

        // Card 1: Account Eraser
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bi("1. Xóa Liên kết Tài khoản (Account ID)", "1. Clear Account Links (Account ID)", lang), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (state.accountKeysFound.isEmpty()) {
                        bi("Không phát hiện khóa tài khoản nào trong playerprefs.xml.", "No account link keys found in playerprefs.xml.", lang)
                    } else {
                        bi(
                            "Phát hiện ${state.accountKeysFound.size} khóa: ${state.accountKeysFound.joinToString(", ")}",
                            "Detected ${state.accountKeysFound.size} keys: ${state.accountKeysFound.joinToString(", ")}",
                            lang
                        )
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = viewModel::clearAccountData,
                    enabled = !state.isLoading && state.accountKeysFound.isNotEmpty()
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Text(bi(" Xóa Account ID", " Clear Account ID", lang))
                }
            }
        }

        // Card 2: SSAID (Device ID) Spoofer — Supports BOTH Plain XML & ABX Binary XML!
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bi("2. Đổi Android ID / SSAID (${state.ssaidFormat.name})", "2. Spoof Android ID / SSAID (${state.ssaidFormat.name})", lang), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = bi(
                        "SSAID hiện tại: ${state.currentSsaid ?: "Không tìm thấy (hãy mở game ít nhất 1 lần)"}",
                        "Current SSAID: ${state.currentSsaid ?: "Not found (launch the game at least once)"}",
                        lang
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                Text(
                    text = bi(
                        "Tệp hệ thống: ${state.ssaidFilePath} • Tự động kiểm chứng round-trip và sao lưu bản gốc trước khi ghi.",
                        "System file: ${state.ssaidFilePath} • Auto round-trip verified with original backup before writing.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customSsaid,
                        onValueChange = { if (it.length <= 16) customSsaid = it },
                        singleLine = true,
                        label = { Text(bi("SSAID tùy chỉnh (16 ký tự hex)", "Custom SSAID (16 hex chars)", lang)) },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.applyNewSsaid(customSsaid) },
                        enabled = !state.isLoading && customSsaid.length == 16 && state.currentSsaid != null
                    ) {
                        Text(bi("Áp dụng", "Apply", lang))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.applyNewSsaid() },
                        enabled = !state.isLoading && state.currentSsaid != null
                    ) {
                        Icon(Icons.Default.Casino, contentDescription = null)
                        Text(bi(" Ngẫu nhiên SSAID", " Randomize SSAID", lang))
                    }
                    if (state.hasOriginalSsaidBackup) {
                        OutlinedButton(onClick = viewModel::restoreOriginalSsaid, enabled = !state.isLoading) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Text(bi(" Khôi phục gốc", " Restore Original", lang))
                        }
                    }
                }
            }
        }

        // Card 3: Reboot Controls (LDPlayer Soft Reboot Highlighted!)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bi("3. Áp dụng thay đổi hệ thống", "3. Apply System Changes", lang), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (state.isEmulator) {
                        bi(
                            "Khuyến nghị trên LDPlayer: dùng Soft Reboot (khởi động lại system_server) để giữ nguyên SSAID mới mà không bị giả lập ghi đè lại khi khởi động lạnh.",
                            "Recommended on LDPlayer: use Soft Reboot (restarts system_server) to preserve the new SSAID without emulator cold-boot reset.",
                            lang
                        )
                    } else {
                        bi(
                            "Sau khi đổi SSAID cần khởi động lại system_server hoặc thiết bị để Android nạp giá trị mới.",
                            "After changing SSAID, restart system_server or reboot so Android reloads the new value.",
                            lang
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmRebootAction = "soft" }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Text(bi(" Soft Reboot (Khuyên dùng)", " Soft Reboot (Recommended)", lang))
                    }
                    OutlinedButton(onClick = { confirmRebootAction = "full" }) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Text(bi(" Reboot máy ảo", " Full Reboot", lang))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    confirmRebootAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmRebootAction = null },
            title = {
                Text(
                    if (action == "soft") {
                        bi("Xác nhận Soft Reboot?", "Confirm Soft Reboot?", lang)
                    } else {
                        bi("Xác nhận Reboot thiết bị?", "Confirm Full Reboot?", lang)
                    }
                )
            },
            text = {
                Text(
                    if (action == "soft") {
                        bi(
                            "Lệnh 'killall system_server' sẽ khởi động lại giao diện Android trong vài giây để nạp SSAID mới.",
                            "'killall system_server' will restart the Android UI in a few seconds to load the new SSAID.",
                            lang
                        )
                    } else {
                        bi(
                            "Toàn bộ máy ảo/thiết bị sẽ khởi động lại ngay bây giờ.",
                            "The entire emulator/device will reboot right now.",
                            lang
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (action == "soft") viewModel.softReboot() else viewModel.fullReboot()
                    confirmRebootAction = null
                }) { Text(bi("Thực hiện", "Confirm", lang)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRebootAction = null }) { Text(bi("Hủy", "Cancel", lang)) }
            }
        )
    }
}
