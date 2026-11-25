package com.dmedit.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmedit.core.shell.AppLanguage
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.bi

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lang by AppLocaleHolder.language.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var customToyboxInput by remember(state.settings.customToyboxCommand) {
        mutableStateOf(state.settings.customToyboxCommand)
    }
    var showLogDialog by remember { mutableStateOf(false) }

    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importExternalProfile)
    }
    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importSoulKnightPresetJson)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            bi("Cài đặt & Chẩn đoán hệ thống (npdmVNEditor)", "Settings & System Diagnostics (npdmVNEditor)", lang),
            style = MaterialTheme.typography.titleMedium
        )

        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        // 0. Language Selector (VI / EN)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(bi("Ngôn ngữ / Language", "Language / Ngôn ngữ", lang), style = MaterialTheme.typography.titleSmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { option ->
                        FilterChip(
                            selected = lang == option,
                            onClick = { viewModel.setLanguage(option) },
                            label = { Text("${option.nativeName} (${option.shortBadge})") }
                        )
                    }
                }
            }
        }

        // 1. Environment & Root Diagnostics
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        bi("Môi trường & Quyền Root", "Environment & Root Access", lang),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::refreshDiagnostics) {
                        Icon(Icons.Default.Refresh, contentDescription = bi("Kiểm tra lại", "Refresh", lang))
                    }
                }
                val info = state.rootInfo
                Text(
                    bi("Phiên bản ứng dụng: npdmVNEditor v1.2.3 (Final Release)", "App Version: npdmVNEditor v1.2.3 (Final Release)", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    bi("Tác giả: Được tạo bởi Nguyễn Phan Đức Mạnh", "Author: Created by Nguyễn Phan Đức Mạnh", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    bi("Lõi xử lý (Native): ${state.rustCoreBanner}", "Native Core: ${state.rustCoreBanner}", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    bi(
                        "Thiết bị / Giả lập: ${info.emulatorType.displayName} (Android ${info.androidRelease} / API ${info.androidApi})",
                        "Device / Emulator: ${info.emulatorType.displayName} (Android ${info.androidRelease} / API ${info.androidApi})",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    bi(
                        "Trạng thái Root: ${if (info.hasRoot) "ĐÃ CẤP (${info.suVariant})" else "CHƯA CÓ"}",
                        "Root Status: ${if (info.hasRoot) "GRANTED (${info.suVariant})" else "UNAVAILABLE"}",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    bi("Kiến trúc nhân (uname -m): ${info.kernelArch}", "Kernel Arch (uname -m): ${info.kernelArch}", lang),
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Toybox/Busybox: ${info.toyboxSource}", style = MaterialTheme.typography.bodySmall)
                Text(
                    bi(
                        "User ID: ${info.activeUserId} • Thư mục chia sẻ: ${info.sharedFolderPath}",
                        "User ID: ${info.activeUserId} • Shared Folder: ${info.sharedFolderPath}",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // 2. Editor & Safety Preferences
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bi("An toàn & Trình chỉnh sửa", "Safety & Editor Preferences", lang), style = MaterialTheme.typography.titleSmall)
                SettingSwitchRow(
                    bi("Lọc chỉ hiển thị tệp save game", "Filter game save files only", lang),
                    state.settings.filterGameFiles,
                    viewModel::toggleFilterFiles
                )
                SettingSwitchRow(
                    bi("Tự dừng game (force-stop) trước khi ghi tệp", "Auto force-stop game before writing", lang),
                    state.settings.forceStopGameBeforeWrite,
                    viewModel::toggleForceStop
                )
                SettingSwitchRow(
                    bi("Tự động tạo bản rollback trước mỗi lần lưu", "Auto create rollback snapshot before save", lang),
                    state.settings.autoBackupBeforeSave,
                    viewModel::toggleAutoBackup
                )
                SettingSwitchRow(
                    bi("Hiển thị bảng Diff thay đổi trước khi lưu", "Show Diff summary before saving", lang),
                    state.settings.showDiffBeforeSave,
                    viewModel::toggleShowDiff
                )
            }
        }

        // 3. Toybox Override
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bi("Cấu hình Toybox / Busybox", "Toybox / Busybox Override", lang), style = MaterialTheme.typography.titleSmall)
                SettingSwitchRow(
                    label = bi("Dùng lệnh Toybox/Busybox tùy chỉnh", "Use custom Toybox/Busybox binary", lang),
                    checked = state.settings.useCustomToybox,
                    onCheckedChange = { viewModel.updateCustomToybox(it, customToyboxInput) }
                )
                if (state.settings.useCustomToybox) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customToyboxInput,
                            onValueChange = { customToyboxInput = it },
                            singleLine = true,
                            label = { Text(bi("VD: /system/bin/toybox hoặc busybox", "e.g. /system/bin/toybox or busybox", lang)) },
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { viewModel.updateCustomToybox(true, customToyboxInput) }) {
                            Text(bi("Áp dụng", "Apply", lang))
                        }
                    }
                }
            }
        }

        // 4. Game Profile Manager
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        bi("Game Profiles đã nạp (${state.profiles.size})", "Loaded Game Profiles (${state.profiles.size})", lang),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { profilePicker.launch(arrayOf("application/json", "application/zip", "*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(bi(" Nhập Profile", " Import Profile", lang))
                    }
                }
                state.profiles.forEach { bundle ->
                    HorizontalDivider()
                    val origin = if (bundle.isExternal) bi("Nhập ngoài", "External", lang) else bi("Tích hợp sẵn", "Built-in", lang)
                    Text(
                        text = "${bundle.profile.displayName} (${bundle.profile.id}) — $origin",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 4.5 Dynamic Soul Knight Preset Updater (8.9 / 9.0+ ready without rebuilding APK)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    bi("Cập nhật Preset Soul Knight (không cần build lại APK)", "Soul Knight Preset Updater (no APK rebuild needed)", lang),
                    style = MaterialTheme.typography.titleSmall
                )
                val pInfo = state.presetOverrideInfo
                Text(
                    text = if (pInfo.hasOverride) {
                        bi(
                            "Đang dùng Preset mở rộng: ${pInfo.presetGroupsCount} nhóm / ${pInfo.totalEntriesCount} mục (gộp tự động lên trên bộ 8.8.0 gốc).",
                            "Using extended Preset override: ${pInfo.presetGroupsCount} groups / ${pInfo.totalEntriesCount} items (merged on top of built-in 8.8.0 pack).",
                            lang
                        )
                    } else {
                        bi(
                            "Đang dùng bộ Preset 8.8.0 tích hợp sẵn (31 nhóm tài nguyên & màn chơi). Khi Soul Knight ra bản mới 8.9/9.0+, bạn có thể quét từ save mới hoặc nhập tệp JSON tại đây.",
                            "Using built-in 8.8.0 Preset pack (31 resource & mode groups). When Soul Knight updates to 8.9/9.0+, scan a live save or import a JSON file here.",
                            lang
                        )
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = viewModel::extractPresetsFromLiveSoulKnightSave,
                        enabled = !state.isBusyPreset && state.rootInfo.hasRoot
                    ) {
                        Text(bi("Quét Save máy → Cập nhật Preset", "Scan Live Save → Update Preset", lang))
                    }
                    OutlinedButton(
                        onClick = { presetPicker.launch(arrayOf("application/json", "*/*")) },
                        enabled = !state.isBusyPreset
                    ) {
                        Text(bi("Nhập tệp Preset JSON", "Import Preset JSON", lang))
                    }
                    if (pInfo.hasOverride) {
                        OutlinedButton(
                            onClick = viewModel::resetSoulKnightPresetOverride,
                            enabled = !state.isBusyPreset
                        ) {
                            Text(bi("Khôi phục mặc định", "Reset Default", lang))
                        }
                    }
                }
            }
        }

        // 5. Crash Logs
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(bi("Nhật ký lỗi (Crash Log)", "Crash Logs", lang), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (state.latestCrashLog != null) {
                            bi("Có tệp latest.log", "latest.log available", lang)
                        } else {
                            bi("Chưa ghi nhận lỗi crash nào.", "No crashes recorded.", lang)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = { showLogDialog = true },
                    enabled = state.latestCrashLog != null
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Text(bi(" Xem Log", " View Log", lang))
                }
            }
        }

        // 6. Security Commitment & Legal Disclaimer
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        bi("Cam kết an toàn & Miễn trừ trách nhiệm", "Security Commitment & Disclaimer", lang),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                HorizontalDivider()
                Text(
                    bi("🛡️ Cam kết từ tác giả:", "🛡️ Author Commitment:", lang),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi(
                        "• Phần mềm sạch 100%, KHÔNG chứa mã độc, virus, phần mềm gián điệp hay quảng cáo.\n" +
                            "• Chạy hoàn toàn offline trên máy — KHÔNG thu thập hay gửi dữ liệu cá nhân ra ngoài.\n" +
                            "• Quyền Root chỉ dùng để chỉnh sửa save game khi bạn bấm nút xác nhận.",
                        "• 100% clean — NO malware, viruses, spyware, or ads.\n" +
                            "• Runs completely offline — collects or transmits ZERO personal data.\n" +
                            "• Root permission is only used to modify game saves when you explicitly tap an action.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi("⚠️ Lưu ý an toàn nhanh:", "⚠️ Quick Safety Tips:", lang),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi(
                        "• Luôn sửa qua tab 'Sửa Nhanh' (tự khóa đúng UID) — tránh sửa các file báo đỏ 'DATA MẶC ĐỊNH'.\n" +
                            "• Không nên sửa Cá khô (Fish Chips) và luôn giữ bản sao lưu .skdb trước khi tải lên Cloud.",
                        "• Always edit via 'Quick Mod' (strict UID lock) — avoid red-flagged 'DEFAULT DATA' files.\n" +
                            "• Avoid modifying Fish Chips and always keep a .skdb backup before Cloud upload.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi("⚖️ Miễn trừ trách nhiệm:", "⚖️ Disclaimer:", lang),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    bi(
                        "• Tác giả (Nguyễn Phan Đức Mạnh) KHÔNG chịu bất kỳ trách nhiệm nào nếu người dùng sử dụng phần mềm dẫn đến bị khóa/ban tài khoản, mất dữ liệu hoặc sự cố từ nhà phát hành game.\n" +
                            "• Người dùng tự chịu hoàn toàn trách nhiệm khi sử dụng. Phần mềm không liên kết với ChillyRoom.",
                        "• The author (Nguyễn Phan Đức Mạnh) assumes NO responsibility for any account ban, data loss, or issues caused by using this software.\n" +
                            "• Use at your own risk. This tool is independent and not affiliated with ChillyRoom.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi("📜 Giấy phép PolyForm Noncommercial 1.0.0 (Ghi công — Phi thương mại):", "📜 PolyForm Noncommercial 1.0.0 License:", lang),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    bi(
                        "• © 2026 Nguyễn Phan Đức Mạnh — Được phép sửa đổi/build lại với điều kiện giữ nguyên ghi công tác giả gốc (LICENSE & NOTICE) và KHÔNG sử dụng cho mục đích thương mại (cấm bán lại, cấm chèn quảng cáo kiếm tiền).\n" +
                            "• Tác giả không chịu trách nhiệm với các bản APK bị bên thứ ba chỉnh sửa/đóng gói lại (repack).",
                        "• © 2026 Nguyễn Phan Đức Mạnh — Reuse & modifications permitted only with original author attribution (LICENSE & NOTICE) and strictly for NON-COMMERCIAL use.\n" +
                            "• Original author is not responsible for third-party modified/repacked APK builds.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                HorizontalDivider()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri("https://www.youtube.com/@2ndofSeptember") } }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" YouTube: @2ndofSeptember")
                    }
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri("https://github.com/NPDMVN/npdmVNEditor") } }
                    ) {
                        Text("GitHub: NPDMVN/npdmVNEditor")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("latest.log") },
            text = {
                Text(
                    text = state.latestCrashLog.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) { Text(bi("Đóng", "Close", lang)) }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
