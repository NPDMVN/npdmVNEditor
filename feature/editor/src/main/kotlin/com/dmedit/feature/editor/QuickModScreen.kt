package com.dmedit.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmedit.core.shell.AppLanguage
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.HealthSeverity
import com.dmedit.core.shell.HeroOption
import com.dmedit.core.shell.QuickModPlan
import com.dmedit.core.shell.QuickModTargets
import com.dmedit.core.shell.QuickRollbackSession
import com.dmedit.core.shell.SaveHealthReport
import com.dmedit.core.shell.bi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickModScreen(
    isWideScreen: Boolean,
    viewModel: QuickModViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lang by AppLocaleHolder.language.collectAsStateWithLifecycle()
    val plan = state.plan
    val scan = state.scan
    val busy = state.isApplying || state.isBusyTool

    var showGuideDialog by remember { mutableStateOf(false) }
    var showHeroPickerDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showCloneUidDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Header + Presets + UID Selector + Quick Tools
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            bi("npdmVNEditor • Sửa Nhanh Soul Knight", "npdmVNEditor • Soul Knight Quick Mod", lang),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (scan.hasRoot) {
                                val fmt = if (scan.preferModernFormat) "AES-256 (.data.new)" else "DES (.data)"
                                val author = bi("Được tạo bởi Nguyễn Phan Đức Mạnh", "Created by Nguyễn Phan Đức Mạnh", lang)
                                "${scan.packageName} • $fmt • $author"
                            } else {
                                bi(
                                    "Chưa có quyền Root • Được tạo bởi Nguyễn Phan Đức Mạnh",
                                    "No Root access • Created by Nguyễn Phan Đức Mạnh",
                                    lang
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AssistChip(
                        onClick = viewModel::toggleLanguage,
                        leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                        label = { Text(lang.shortBadge) }
                    )
                    IconButton(onClick = { showGuideDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = bi("Ghi chú & Lưu ý quan trọng", "Notes & Guide", lang))
                    }
                    IconButton(onClick = { viewModel.rescan() }, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = bi("Quét lại save", "Rescan save", lang))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(
                        onClick = viewModel::selectNoIapSafe,
                        enabled = !busy,
                        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                        label = { Text(bi("No-IAP (Nick chính)", "No-IAP (Main Acc)", lang)) }
                    )
                    AssistChip(
                        onClick = viewModel::selectCloudSafe,
                        enabled = !busy,
                        leadingIcon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                        label = { Text(bi("Chuẩn Cloud", "Cloud Safe", lang)) }
                    )
                    AssistChip(
                        onClick = viewModel::selectAllMax,
                        enabled = !busy,
                        leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                        label = { Text("Full Offline") }
                    )
                    AssistChip(
                        onClick = viewModel::selectNone,
                        enabled = !busy,
                        leadingIcon = { Icon(Icons.Default.Deselect, contentDescription = null) },
                        label = { Text(bi("Bỏ chọn", "Clear All", lang)) }
                    )
                }

                if (scan.availableUids.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = bi("Tài khoản UID:", "Account UID:", lang),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        scan.availableUids.forEach { uid ->
                            FilterChip(
                                selected = uid == scan.selectedUid,
                                onClick = { viewModel.selectUid(uid) },
                                enabled = !busy,
                                label = { Text(if (uid.isBlank()) bi("Khách (không UID)", "Guest (no UID)", lang) else "UID $uid") }
                            )
                        }
                    }
                    if (scan.selectedUid.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = bi(
                                "🔒 Khóa chặt theo UID ${scan.selectedUid} — tuyệt đối KHÔNG sửa các file mặc định không có UID",
                                "🔒 Locked strictly to UID ${scan.selectedUid} — NEVER touches default no-UID files",
                                lang
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(
                        onClick = viewModel::runHealthCheck,
                        enabled = scan.hasRoot && !busy,
                        leadingIcon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null) },
                        label = { Text(bi("Khám sức khỏe Save", "Save Health Check", lang)) }
                    )
                    AssistChip(
                        onClick = { showCloneUidDialog = true },
                        enabled = scan.hasRoot && scan.selectedUid.isNotBlank() && !busy,
                        leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                        label = { Text(bi("Chuyển / Nhập UID", "Clone / Import UID", lang)) }
                    )
                    AssistChip(
                        onClick = { showHistoryDialog = true },
                        enabled = !busy,
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        label = { Text(bi("Lịch sử hoàn tác (${state.rollbackHistory.size})", "Rollback History (${state.rollbackHistory.size})", lang)) }
                    )
                    AssistChip(
                        onClick = viewModel::launchSoulKnight,
                        enabled = scan.packageName.isNotBlank() && !busy,
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        label = { Text(bi("Mở Soul Knight", "Launch Soul Knight", lang)) }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                DetectedFilesStrip(targets = scan.targets, lang = lang)
            }
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.errorMessage?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
        state.statusMessage?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.lastApplySucceeded && scan.packageName.isNotBlank()) {
                        TextButton(onClick = viewModel::launchSoulKnight) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(bi("Mở Game", "Launch", lang))
                        }
                    }
                }
            }
        }

        // Main Checklist Content
        if (state.isScanning) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ImportantNotesCard(
                    selectedUid = scan.selectedUid,
                    targets = scan.targets,
                    autoCreateEnabled = plan.autoCreateMissingModeFiles,
                    lang = lang,
                    onOpenFullGuide = { showGuideDialog = true }
                )

                if (isWideScreen) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            HeroesAndRoomCard(
                                plan = plan,
                                totalHeroesCount = scan.availableHeroes.size.coerceAtLeast(42),
                                enabled = !busy,
                                lang = lang,
                                onUpdate = viewModel::updatePlan,
                                onOpenHeroPicker = { showHeroPickerDialog = true }
                            )
                            CurrenciesCard(plan, !busy, lang, viewModel::updatePlan)
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            InventoryAndGearCard(plan, !busy, lang, viewModel::updatePlan)
                            WeaponsAndModesCard(plan, !busy, lang, viewModel::updatePlan)
                        }
                    }
                } else {
                    HeroesAndRoomCard(
                        plan = plan,
                        totalHeroesCount = scan.availableHeroes.size.coerceAtLeast(42),
                        enabled = !busy,
                        lang = lang,
                        onUpdate = viewModel::updatePlan,
                        onOpenHeroPicker = { showHeroPickerDialog = true }
                    )
                    CurrenciesCard(plan, !busy, lang, viewModel::updatePlan)
                    InventoryAndGearCard(plan, !busy, lang, viewModel::updatePlan)
                    WeaponsAndModesCard(plan, !busy, lang, viewModel::updatePlan)
                }

                CloudSafetyAuditCard(plan = plan, lang = lang)

                if (state.progressLogs.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(bi("Nhật ký áp dụng", "Execution Log", lang), style = MaterialTheme.typography.titleSmall)
                            HorizontalDivider()
                            state.progressLogs.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sticky Bottom Action Bar
        Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = plan.autoCreateSkdbBackup,
                        onClick = { viewModel.updatePlan { it.copy(autoCreateSkdbBackup = !it.autoCreateSkdbBackup) } },
                        enabled = !busy,
                        label = { Text(bi("Tự backup .skdb", "Auto .skdb", lang), style = MaterialTheme.typography.labelSmall) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (state.rollbackHistory.isNotEmpty()) {
                        OutlinedButton(
                            onClick = viewModel::rollbackLatestSession,
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.History, contentDescription = null)
                            Text(bi(" Hoàn tác", " Rollback", lang))
                        }
                    }
                    Button(
                        onClick = viewModel::applySelected,
                        enabled = scan.hasRoot && !state.isScanning && !busy && plan.selectedCount > 0
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        Text(bi(" Áp dụng (${plan.selectedCount})", " Apply (${plan.selectedCount})", lang))
                    }
                }
            }
        }
    }

    if (showGuideDialog) {
        SoulKnightNotesDialog(
            selectedUid = scan.selectedUid,
            lang = lang,
            onDismiss = { showGuideDialog = false }
        )
    }

    state.healthReport?.let { report ->
        SaveHealthDialog(
            report = report,
            isBusy = busy,
            lang = lang,
            onRepair = viewModel::repairHealthIssues,
            onDismiss = viewModel::dismissHealthReport
        )
    }

    if (showHeroPickerDialog) {
        HeroPickerDialog(
            heroes = scan.availableHeroes,
            selectedKeys = plan.selectedHeroes,
            lang = lang,
            onToggle = viewModel::toggleHeroSelection,
            onClearAll = viewModel::clearHeroSelection,
            onDismiss = { showHeroPickerDialog = false }
        )
    }

    if (showHistoryDialog) {
        RollbackHistoryDialog(
            sessions = state.rollbackHistory,
            lang = lang,
            onRestore = { session ->
                showHistoryDialog = false
                viewModel.rollbackSession(session)
            },
            onDismiss = { showHistoryDialog = false }
        )
    }

    if (showCloneUidDialog) {
        CloneUidDialog(
            targetUid = scan.selectedUid,
            otherLocalUids = scan.availableUids.filter { it.isNotBlank() && !it.equals(scan.selectedUid, ignoreCase = true) },
            skdbFiles = state.availableSkdbFiles,
            lang = lang,
            onCloneLocal = { srcUid ->
                showCloneUidDialog = false
                viewModel.cloneFromLocalUid(srcUid)
            },
            onImportSkdb = { file ->
                showCloneUidDialog = false
                viewModel.importSkdbToCurrentUid(file)
            },
            onDismiss = { showCloneUidDialog = false }
        )
    }
}

@Composable
private fun ImportantNotesCard(
    selectedUid: String,
    targets: QuickModTargets,
    autoCreateEnabled: Boolean,
    lang: AppLanguage,
    onOpenFullGuide: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = bi(
                        "Lưu ý & Miễn trừ trách nhiệm (bấm để ${if (expanded) "thu gọn" else "xem"})",
                        "Notes & Disclaimer (tap to ${if (expanded) "collapse" else "view"})",
                        lang
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    bi(
                        "• Cách dùng nhanh: Đăng nhập game → chọn mục cần sửa → bấm 'Áp dụng' → mở game chơi.",
                        "• Quick steps: Sign in to the game → pick options → tap 'Apply' → launch game.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi(
                        "• Tránh rủi ro: Không chỉnh Cá khô (tiền nạp), không mở các tệp báo đỏ trong tab Tệp Save. Nên giữ bật 'Tự backup .skdb' để luôn quay lại bản gốc được.",
                        "• Stay safe: Avoid editing Fish Chips (paid currency) and red-flagged default files. Keep 'Auto .skdb' ON so you can always restore.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi(
                        "• An toàn thiết bị: Phần mềm sạch 100%, không chứa mã độc, virus, quảng cáo và không thu thập dữ liệu người dùng.",
                        "• Clean & Private: 100% malware-free, no viruses, no ads, works completely offline and collects zero personal data.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    bi(
                        "• Miễn trừ trách nhiệm: Tác giả (Nguyễn Phan Đức Mạnh) không chịu bất kỳ trách nhiệm nào nếu tài khoản bị khóa/ban khi sử dụng phần mềm.",
                        "• Disclaimer: The author (Nguyễn Phan Đức Mạnh) assumes NO responsibility for any account ban or data loss resulting from using this app.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenFullGuide) {
                        Text(bi("Xem chi tiết", "Read Full Notice", lang))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoulKnightNotesDialog(
    selectedUid: String,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text(bi("Thông tin & Lưu ý — npdmVNEditor", "About & Notice — npdmVNEditor", lang)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    bi(
                        "npdmVNEditor v1.2.3 • Được tạo bởi Nguyễn Phan Đức Mạnh",
                        "npdmVNEditor v1.2.3 • Created by Nguyễn Phan Đức Mạnh",
                        lang
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(
                        onClick = { runCatching { uriHandler.openUri("https://www.youtube.com/@2ndofSeptember") } },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        label = { Text("YouTube: @2ndofSeptember") }
                    )
                    AssistChip(
                        onClick = { runCatching { uriHandler.openUri("https://github.com/NPDMVN/npdmVNEditor") } },
                        label = { Text("GitHub: NPDMVN/npdmVNEditor") }
                    )
                }
                HorizontalDivider()

                Text(
                    bi("📌 Hướng dẫn nhanh (3 bước)", "📌 Quick Guide (3 Steps)", lang),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    bi(
                        "1. Mở Soul Knight, đăng nhập tài khoản trước rồi thoát game.\n" +
                            "2. Mở npdmVNEditor, chọn gói phù hợp ('No-IAP' cho nick chính hoặc 'Full Offline' để mở hết).\n" +
                            "3. Bấm 'Áp dụng' rồi mở game chơi. Nếu muốn quay lại như cũ, bấm 'Hoàn tác'.",
                        "1. Open Soul Knight, sign in to your account, then close the game.\n" +
                            "2. Open npdmVNEditor and pick a preset ('No-IAP' for main account or 'Full Offline' to unlock all).\n" +
                            "3. Tap 'Apply' and launch the game. Tap 'Rollback' anytime to restore.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    bi("⚠️ Lưu ý quan trọng khi sử dụng", "⚠️ Important Usage Tips", lang),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    bi(
                        "• Luôn dùng tab Sửa Nhanh để app tự chọn đúng tài khoản (UID hiện tại: ${selectedUid.ifBlank { "chưa phát hiện" }}).\n" +
                            "• Không tự ý sửa các tệp tô đỏ 'DATA MẶC ĐỊNH' ở tab Tệp Save.\n" +
                            "• Không nên chỉnh Cá khô (Fish Chips) vì đây là tiền nạp thật.\n" +
                            "• Nên chạy 'Khám sức khỏe Save' trước khi tải dữ liệu lên Cloud.",
                        "• Use Quick Mod so the app locks onto your exact account (current UID: ${selectedUid.ifBlank { "none" }}).\n" +
                            "• Do not edit red-flagged 'DEFAULT DATA' files in Save Files.\n" +
                            "• Avoid modifying Fish Chips (real-money currency).\n" +
                            "• Run 'Save Health Check' before uploading to Cloud.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    bi("🛡️ Cam kết an toàn & Quyền riêng tư", "🛡️ Security & Privacy Commitment", lang),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    bi(
                        "• Phần mềm hoàn toàn sạch, KHÔNG chứa mã độc, virus, trojan hay quảng cáo.\n" +
                            "• Hoạt động 100% offline trên thiết bị — KHÔNG thu thập dữ liệu cá nhân, mật khẩu hay thông tin tài khoản.\n" +
                            "• Quyền Root chỉ dùng để đọc/ghi tệp save game khi bạn chủ động bấm nút thao tác.",
                        "• 100% clean software — contains NO malware, viruses, trojans, or ads.\n" +
                            "• Runs 100% offline on your device — collects NO personal data, passwords, or account info.\n" +
                            "• Root access is used strictly to read/write game save files only when you tap an action.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    bi("⚖️ Miễn trừ trách nhiệm", "⚖️ Disclaimer", lang),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    bi(
                        "• Phần mềm được tạo ra chỉ nhằm mục đích trải nghiệm cá nhân và nghiên cứu kỹ thuật.\n" +
                            "• Việc chỉnh sửa dữ liệu game có thể vi phạm điều khoản của nhà phát hành và dẫn đến khóa/ban tài khoản.\n" +
                            "• Tác giả (Nguyễn Phan Đức Mạnh) KHÔNG chịu bất kỳ trách nhiệm nào nếu người dùng sử dụng phần mềm và bị ban tài khoản, mất dữ liệu hoặc gặp sự cố phát sinh.\n" +
                            "• Ứng dụng độc lập, không liên kết hay thuộc sở hữu của ChillyRoom.",
                        "• This software is provided solely for personal offline experimentation and educational purposes.\n" +
                            "• Modifying game data may violate the game publisher's Terms of Service and can result in an account ban.\n" +
                            "• The author (Nguyễn Phan Đức Mạnh) is NOT responsible in any way for account bans, data loss, or any consequences resulting from using this software.\n" +
                            "• Independent tool — not affiliated with or endorsed by ChillyRoom.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    bi("📜 Bản quyền & Điều khoản tái sử dụng", "📜 Copyright & Reuse License", lang),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    bi(
                        "• © 2026 Nguyễn Phan Đức Mạnh — npdmVNEditor (Giấy phép quốc tế PolyForm Noncommercial 1.0.0).\n" +
                            "• Được phép sử dụng lại mã nguồn, chỉnh sửa và thêm tính năng cho mục đích học tập/cá nhân, BẮT BUỘC giữ nguyên ghi công tác giả gốc (Nguyễn Phan Đức Mạnh) cùng tệp LICENSE & NOTICE.\n" +
                            "• NGHIÊM CẤM dùng cho mục đích thương mại (bán lại, thu phí tải/mở khóa, chèn quảng cáo kiếm tiền) hoặc chèn mã độc.\n" +
                            "• Cảnh báo bản Repack: Tác giả gốc không chịu trách nhiệm đối với các bản APK bị bên thứ ba chỉnh sửa hoặc đóng gói lại.",
                        "• © 2026 Nguyễn Phan Đức Mạnh — npdmVNEditor (PolyForm Noncommercial License 1.0.0).\n" +
                            "• You may reuse, modify, and add features for personal/educational use, provided you KEEP original author attribution (Nguyễn Phan Đức Mạnh) and the LICENSE & NOTICE files.\n" +
                            "• Commercial use (selling, charging fees, monetized ads) and malware injection are STRICTLY PROHIBITED.\n" +
                            "• Repack Warning: The original author is not responsible for third-party modified or repacked APK builds.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(bi("Tôi đã đọc & hiểu", "I Understand", lang)) }
        }
    )
}

@Composable
private fun SaveHealthDialog(
    report: SaveHealthReport,
    isBusy: Boolean,
    lang: AppLanguage,
    onRepair: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null) },
        title = {
            Text(
                bi(
                    "Khám sức khỏe Save (UID ${report.uid.ifBlank { "Khách" }})",
                    "Save Health Check (UID ${report.uid.ifBlank { "Guest" }})",
                    lang
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                report.items.forEach { item ->
                    val prefix = when (item.severity) {
                        HealthSeverity.OK -> "✅"
                        HealthSeverity.WARNING -> "⚠️"
                        HealthSeverity.CRITICAL -> "⛔"
                    }
                    val color = when (item.severity) {
                        HealthSeverity.OK -> MaterialTheme.colorScheme.primary
                        HealthSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                        HealthSeverity.CRITICAL -> MaterialTheme.colorScheme.error
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("$prefix ${item.title}", style = MaterialTheme.typography.titleSmall, color = color)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            if (report.hasFixableIssues) {
                Button(onClick = onRepair, enabled = !isBusy) {
                    Text(bi("Sửa lỗi cấu trúc ngay", "Auto-Repair Issues", lang))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(bi("Đóng", "Close", lang)) }
            }
        },
        dismissButton = {
            if (report.hasFixableIssues) {
                TextButton(onClick = onDismiss) { Text(bi("Đóng", "Close", lang)) }
            }
        }
    )
}

@Composable
private fun HeroPickerDialog(
    heroes: List<HeroOption>,
    selectedKeys: Set<String>,
    lang: AppLanguage,
    onToggle: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(heroes, query, lang) {
        if (query.isBlank()) heroes else heroes.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.nameEn.contains(query, ignoreCase = true) ||
                it.key.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.People, contentDescription = null) },
        title = {
            val countTxt = if (selectedKeys.isEmpty()) bi("Tất cả", "All", lang) else bi("${selectedKeys.size} tướng", "${selectedKeys.size} selected", lang)
            Text(bi("Chọn lẻ nhân vật áp dụng ($countTxt)", "Select Specific Heroes ($countTxt)", lang))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(bi("Tìm tên tướng (VD: Hiệp sĩ, Knight, Sát thủ)...", "Search hero (e.g. Knight, Rogue, Assassin)...", lang)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    bi(
                        "Bỏ trống tất cả = áp dụng cho toàn bộ ${heroes.size} nhân vật.",
                        "Leave none selected = apply to all ${heroes.size} heroes.",
                        lang
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(filtered, key = { it.key }) { hero ->
                        val checked = hero.key in selectedKeys
                        val mainName = hero.localizedName(lang)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(hero.key) }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onToggle(hero.key) })
                            Column {
                                Text(mainName, style = MaterialTheme.typography.bodyMedium)
                                if (mainName != hero.key) {
                                    Text(
                                        hero.key,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(bi("Xong", "Done", lang)) }
        },
        dismissButton = {
            if (selectedKeys.isNotEmpty()) {
                TextButton(onClick = onClearAll) { Text(bi("Chọn tất cả (mặc định)", "Select All (Default)", lang)) }
            }
        }
    )
}

@Composable
private fun RollbackHistoryDialog(
    sessions: List<QuickRollbackSession>,
    lang: AppLanguage,
    onRestore: (QuickRollbackSession) -> Unit,
    onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.History, contentDescription = null) },
        title = { Text(bi("Lịch sử hoàn tác bền vững (${sessions.size})", "Persistent Rollback History (${sessions.size})", lang)) },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    bi(
                        "Chưa có phiên sửa nhanh nào được lưu. Mỗi lần bấm 'Áp dụng', bản gốc sẽ được lưu tự động tại đây.",
                        "No rollback sessions yet. Original files are automatically saved here every time you tap 'Apply'.",
                        lang
                    )
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        ListItem(
                            headlineContent = { Text(s.summary, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = {
                                Text(
                                    "${fmt.format(Date(s.timestampMs))} • UID ${s.uid.ifBlank { bi("Khách", "Guest", lang) }} • ${s.entries.size} ${bi("tệp", "files", lang)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = { onRestore(s) }) {
                                    Text(bi("Khôi phục", "Restore", lang))
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(bi("Đóng", "Close", lang)) }
        }
    )
}

@Composable
private fun CloneUidDialog(
    targetUid: String,
    otherLocalUids: List<String>,
    skdbFiles: List<File>,
    lang: AppLanguage,
    onCloneLocal: (String) -> Unit,
    onImportSkdb: (File) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
        title = { Text(bi("Chuyển / Nhập Save sang UID $targetUid", "Clone / Import Save to UID $targetUid", lang)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    bi(
                        "Tự động đổi đuôi _UID_.data.new, đổi tiền tố playerprefs.xml sang '${targetUid}_' và loại bỏ hoàn toàn thông tin đăng nhập / biên lai tài khoản cũ.",
                        "Automatically remaps _UID_.data.new filenames, updates playerprefs.xml prefix to '${targetUid}_', and strips all old account tokens/receipts.",
                        lang
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (otherLocalUids.isNotEmpty()) {
                    Text(
                        bi("1. Chuyển từ UID khác có sẵn trên máy:", "1. Clone from another local UID:", lang),
                        style = MaterialTheme.typography.titleSmall
                    )
                    otherLocalUids.forEach { srcUid ->
                        OutlinedButton(
                            onClick = { onCloneLocal(srcUid) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(bi("Chuyển UID $srcUid → UID $targetUid", "Clone UID $srcUid → UID $targetUid", lang))
                        }
                    }
                }
                Text(
                    bi("2. Nạp từ bản sao lưu .skdb sang UID $targetUid:", "2. Import from .skdb backup into UID $targetUid:", lang),
                    style = MaterialTheme.typography.titleSmall
                )
                if (skdbFiles.isEmpty()) {
                    Text(
                        bi(
                            "Chưa có tệp .skdb nào trong thư mục Sao lưu (tab Backup).",
                            "No .skdb backup archives found yet (check Backups tab).",
                            lang
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    skdbFiles.take(8).forEach { file ->
                        OutlinedButton(
                            onClick = { onImportSkdb(file) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(bi("Nạp ${file.name} → UID $targetUid", "Import ${file.name} → UID $targetUid", lang), maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(bi("Đóng", "Close", lang)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetectedFilesStrip(targets: QuickModTargets, lang: AppLanguage) {
    val emptyWord = bi("trống", "missing", lang)
    val badges = listOf(
        "game.data" to (targets.gameFile != null),
        "playerprefs.xml" to (targets.prefsFile != null),
        "item_data" to (targets.itemFile != null),
        "weapon_evo" to (targets.weaponEvolutionFile != null),
        "statistic" to (targets.statisticFile != null),
        "season" to (targets.seasonFile != null),
        "escape" to (targets.escapeSeasonFile != null),
        "monsrise" to (targets.monsriseFile != null),
        "misc" to (targets.miscFile != null)
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        badges.forEach { (label, found) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (found) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(14.dp).width(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = if (found) label else "$label ($emptyWord)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (found) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroesAndRoomCard(
    plan: QuickModPlan,
    totalHeroesCount: Int,
    enabled: Boolean,
    lang: AppLanguage,
    onUpdate: ((QuickModPlan) -> QuickModPlan) -> Unit,
    onOpenHeroPicker: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        bi("1. Tướng, Skin, Kỹ năng & Phòng khách", "1. Heroes, Skins, Skills & Living Room", lang),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        bi("Tự động đồng bộ game.data ↔ playerprefs.xml", "Auto-synced game.data ↔ playerprefs.xml", lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = onOpenHeroPicker,
                    enabled = enabled,
                    leadingIcon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = {
                        Text(
                            if (plan.selectedHeroes.isEmpty()) {
                                bi("Tất cả ($totalHeroesCount)", "All ($totalHeroesCount)", lang)
                            } else {
                                bi("Chọn lẻ (${plan.selectedHeroes.size})", "Picked (${plan.selectedHeroes.size})", lang)
                            }
                        )
                    }
                )
            }
            if (plan.selectedHeroes.isNotEmpty()) {
                Text(
                    text = bi(
                        "🎯 Đang chọn lẻ ${plan.selectedHeroes.size} nhân vật: ${plan.selectedHeroes.take(5).joinToString(", ")}${if (plan.selectedHeroes.size > 5) "…" else ""}",
                        "🎯 Targeting ${plan.selectedHeroes.size} selected hero(es): ${plan.selectedHeroes.take(5).joinToString(", ")}${if (plan.selectedHeroes.size > 5) "…" else ""}",
                        lang
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val scopeSuffix = if (plan.selectedHeroes.isEmpty()) {
                bi("$totalHeroesCount nhân vật", "$totalHeroesCount heroes", lang)
            } else {
                bi("${plan.selectedHeroes.size} tướng đã chọn", "${plan.selectedHeroes.size} selected", lang)
            }
            CheckRow(bi("Mở khóa Nhân vật ($scopeSuffix)", "Unlock Heroes ($scopeSuffix)", lang), plan.unlockHeroes, enabled) {
                onUpdate { p -> p.copy(unlockHeroes = it) }
            }
            CheckRow(
                bi("Nâng cấp Max cấp độ nhân vật (Lv.7 chuẩn game)", "Upgrade Hero Levels to Max (Legit Lv.7)", lang),
                plan.maxHeroLevels,
                enabled
            ) {
                onUpdate { p -> p.copy(maxHeroLevels = it, heroLevelTarget = 7) }
            }
            CheckRow(
                label = when {
                    plan.gemSkinsOnly -> bi(
                        "Mở khóa Skin mua bằng Gems — No-IAP ($scopeSuffix)",
                        "Unlock Gem-Only Skins — No-IAP ($scopeSuffix)",
                        lang
                    )
                    else -> bi(
                        "Mở khóa Full 100% toàn bộ Skin tướng ($scopeSuffix)",
                        "Unlock Full 100% Hero Skins ($scopeSuffix)",
                        lang
                    )
                },
                checked = plan.unlockSkins,
                enabled = enabled
            ) {
                onUpdate { p -> p.copy(unlockSkins = it) }
            }
            if (plan.unlockSkins) {
                CheckRow(
                    bi("  ↳ Chỉ mở skin mua bằng Gems (No-IAP, bỏ qua skin nạp tiền)", "  ↳ Gem skins only (No-IAP, skip paid/negative skins)", lang),
                    plan.gemSkinsOnly,
                    enabled
                ) {
                    onUpdate { p -> p.copy(gemSkinsOnly = it, skipRestrictedSkins = if (it) true else p.skipRestrictedSkins) }
                }
                if (!plan.gemSkinsOnly) {
                    CheckRow(
                        bi("  ↳ Chỉ mở skin cơ bản (bỏ qua slot 0 / ≤ -6)", "  ↳ Basic skins only (skip 0 / ≤ -6 slots)", lang),
                        plan.skipRestrictedSkins,
                        enabled
                    ) {
                        onUpdate { p -> p.copy(skipRestrictedSkins = it) }
                    }
                }
            }
            CheckRow(bi("Mở khóa Full Kỹ năng 1/2/3 ($scopeSuffix)", "Unlock All Skills 1/2/3 ($scopeSuffix)", lang), plan.unlockSkills, enabled) {
                onUpdate { p -> p.copy(unlockSkills = it) }
            }
            CheckRow(bi("Mở khóa Full 58 Thú cưng", "Unlock All 58 Pets", lang), plan.unlockPets, enabled) {
                onUpdate { p -> p.copy(unlockPets = it) }
            }
            CheckRow(bi("Bật Thẻ Hồi Sinh Miễn Phí (rebornCard)", "Enable Free Revive Card (rebornCard)", lang), plan.enableRebornCard, enabled) {
                onUpdate { p -> p.copy(enableRebornCard = it) }
            }
            CheckRow(bi("Nâng Max cấp 5 toàn bộ 5 đồ Phòng khách", "Max Level 5 All 5 Living Room Furniture", lang), plan.maxRoomObjects, enabled) {
                onUpdate { p -> p.copy(maxRoomObjects = it) }
            }
        }
    }
}

@Composable
private fun CurrenciesCard(
    plan: QuickModPlan,
    enabled: Boolean,
    lang: AppLanguage,
    onUpdate: ((QuickModPlan) -> QuickModPlan) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                bi("2. Tiền tệ (tích ô nào mới sửa ô đó)", "2. Currencies (only checked fields are edited)", lang),
                style = MaterialTheme.typography.titleSmall
            )
            CurrencyCheckRow(
                label = bi("Đá quý (Gems)", "Gems", lang),
                checked = plan.editGems,
                value = plan.gemsValue,
                enabled = enabled,
                onChecked = { c -> onUpdate { it.copy(editGems = c) } },
                onValue = { v -> onUpdate { it.copy(gemsValue = v) } }
            )
            CurrencyCheckRow(
                label = bi("Cá khô / Fish Chips (⚠ Rủi ro cao — tiền IAP)", "Fish Chips (⚠ High Risk — IAP Currency)", lang),
                checked = plan.editFishChips,
                value = plan.fishChipsValue,
                enabled = enabled,
                onChecked = { c -> onUpdate { it.copy(editFishChips = c) } },
                onValue = { v -> onUpdate { it.copy(fishChipsValue = v) } }
            )
            CurrencyCheckRow(
                label = bi("Xu Mùa giải (Season Coin)", "Season Coins", lang),
                checked = plan.editSeasonCoins,
                value = plan.seasonCoinsValue,
                enabled = enabled,
                onChecked = { c -> onUpdate { it.copy(editSeasonCoins = c) } },
                onValue = { v -> onUpdate { it.copy(seasonCoinsValue = v) } }
            )
            CurrencyCheckRow(
                label = bi("Tiền Sắt Đảo Điên (Escape Money)", "Escape Iron Money", lang),
                checked = plan.editEscapeMoney,
                value = plan.escapeMoneyValue,
                enabled = enabled,
                onChecked = { c -> onUpdate { it.copy(editEscapeMoney = c) } },
                onValue = { v -> onUpdate { it.copy(escapeMoneyValue = v) } }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InventoryAndGearCard(
    plan: QuickModPlan,
    enabled: Boolean,
    lang: AppLanguage,
    onUpdate: ((QuickModPlan) -> QuickModPlan) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                bi("3. Kho đồ, Bản vẽ, Trang sức & Vườn", "3. Inventory, Blueprints, Jewelry & Garden", lang),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                bi("Tự sửa đúng tệp item_data theo UID & định dạng (.data.new / .data)", "Auto-targets UID item_data & format (.data.new / .data)", lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            CheckRow(
                bi(
                    "Nạp Full 133 Nguyên liệu, Mảnh phép, Mảnh skin & Băng nhạc (x${plan.materialQty})",
                    "Add All 133 Materials, Fragments, Skill Books & Tapes (x${plan.materialQty})",
                    lang
                ),
                plan.fullMaterials,
                enabled
            ) {
                onUpdate { p -> p.copy(fullMaterials = it) }
            }
            if (plan.fullMaterials) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 40.dp, bottom = 4.dp)
                ) {
                    listOf(200, 500, 999).forEach { qty ->
                        FilterChip(
                            selected = plan.materialQty == qty,
                            onClick = { onUpdate { p -> p.copy(materialQty = qty) } },
                            enabled = enabled,
                            label = { Text("x$qty") }
                        )
                    }
                }
            }
            CheckRow(
                bi("Nạp Full 47 Hạt giống vườn (x${plan.seedQty})", "Add All 47 Garden Seeds (x${plan.seedQty})", lang),
                plan.fullSeeds,
                enabled
            ) {
                onUpdate { p -> p.copy(fullSeeds = it) }
            }
            CheckRow(
                bi("Nạp & Mở khóa Full 280 Bản vẽ (Researched)", "Unlock All 280 Blueprints (Researched)", lang),
                plan.fullBlueprints,
                enabled
            ) {
                onUpdate { p -> p.copy(fullBlueprints = it) }
            }
            CheckRow(
                bi("Nạp Full 229 Phiếu vũ khí/hệ số (x${plan.ticketQty})", "Add All 229 Vouchers & Tickets (x${plan.ticketQty})", lang),
                plan.fullTickets,
                enabled
            ) {
                onUpdate { p -> p.copy(fullTickets = it) }
            }
            CheckRow(
                bi("Mở khóa Full 6 Ô Vườn, Xe Máy & Đòn tay không", "Unlock All 6 Garden Plots, Motorcycle & Bare-Hand Styles", lang),
                plan.unlockGardenAndMoto,
                enabled
            ) {
                onUpdate { p -> p.copy(unlockGardenAndMoto = it) }
            }
            CheckRow(
                bi("Mở khóa Full 32 Vũ khí Thần thoại cấp 3", "Unlock All 32 Mythic Weapons (Lv.3)", lang),
                plan.fullMythicWeapons,
                enabled
            ) {
                onUpdate { p -> p.copy(fullMythicWeapons = it) }
            }
            CheckRow(
                bi("Mở khóa Full 15 Trang sức (độ bền chuẩn 100)", "Unlock All 15 Jewelry (Legit Durability 100)", lang),
                plan.fullJewelry,
                enabled
            ) {
                onUpdate { p -> p.copy(fullJewelry = it) }
            }
        }
    }
}

@Composable
private fun WeaponsAndModesCard(
    plan: QuickModPlan,
    enabled: Boolean,
    lang: AppLanguage,
    onUpdate: ((QuickModPlan) -> QuickModPlan) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                bi("4. Màn chơi, Chế độ, Vũ khí & Mùa giải", "4. Stages, Modes, Weapons & Seasons", lang),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                bi("Tự động mở khóa các màn/chế độ & vá từng file chuyên biệt đúng UID", "Auto-unlocks modes/stages & patches UID shard files", lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            CheckRow(
                bi(
                    "Mở Full Chế độ chơi, Sảnh & Độ khó (Badass, Boss Rush, Cội Nguồn, Ma Trận, Tier 1–3)",
                    "Unlock All Game Modes, Lobby Areas & Tiers (Badass, Boss Rush, Origin, Matrix, Tier 1–3)",
                    lang
                ),
                plan.unlockAllGameModes,
                enabled
            ) {
                onUpdate { p -> p.copy(unlockAllGameModes = it) }
            }
            CheckRow(
                bi(
                    "Mở Full 5 Bản đồ, 3 Trụ, 15 Tướng & 22 Nâng cấp Đảo Điên (Escape)",
                    "Unlock All 5 Escape Maps, 3 Beacons, 15 Heroes & 22 Base Upgrades",
                    lang
                ),
                plan.unlockEscapeMapsAndBase,
                enabled
            ) {
                onUpdate { p -> p.copy(unlockEscapeMapsAndBase = it) }
            }
            CheckRow(
                bi(
                    "Tự khởi tạo file Đảo Điên / Quái vật / Hiệu ứng nếu nick mới chưa có",
                    "Auto-create missing Escape / Monster Rise / Misc files for fresh accounts",
                    lang
                ),
                plan.autoCreateMissingModeFiles,
                enabled
            ) {
                onUpdate { p -> p.copy(autoCreateMissingModeFiles = it) }
            }
            CheckRow(
                bi("Mở Full 175 Vũ khí Tiến hóa & Skin vũ khí", "Unlock All 175 Weapon Evolutions & Weapon Skins", lang),
                plan.fullWeaponEvolutions,
                enabled
            ) {
                onUpdate { p -> p.copy(fullWeaponEvolutions = it) }
            }
            CheckRow(
                bi("Mở Rèn & Sổ tay Full 1.038 Vũ khí (Bàn Rèn + Hầm ≥ 8 lần)", "Unlock All 1,038 Weapons in Forge Table & Cellar (≥ 8 pickups)", lang),
                plan.unlockAllForgeWeapons,
                enabled
            ) {
                onUpdate { p -> p.copy(unlockAllForgeWeapons = it) }
            }
            CheckRow(
                bi("Nạp Full 20 Chip Lv.10 + Kỹ năng Mùa giải", "Max All 20 Season Chips (Lv.10) + Season Skills", lang),
                plan.fullSeasonChipsAndSkills,
                enabled
            ) {
                onUpdate { p -> p.copy(fullSeasonChipsAndSkills = it) }
            }
            CheckRow(
                bi("Mở rộng Túi/Kho/Két Đảo Điên (70 / 320 / 2 — chuẩn game)", "Expand Escape Storage (70 Bag / 320 Warehouse / 2 Safe)", lang),
                plan.expandEscapeStorage,
                enabled
            ) {
                onUpdate { p -> p.copy(expandEscapeStorage = it) }
            }
            CheckRow(
                bi("Nạp Full 25 Quái vật Lv.5, 35 Ấn ký Lv.8 & 28 Màn Quái vật", "Max 25 Monsters (Lv.5), 35 Emblems (Lv.8) & 28 Monster Rise Stages", lang),
                plan.fullMonsterRise,
                enabled
            ) {
                onUpdate { p -> p.copy(fullMonsterRise = it) }
            }
            CheckRow(
                bi("Mở khóa Full 11 Hiệu ứng kết liễu (misc_data)", "Unlock All 11 Kill Effects (misc_data)", lang),
                plan.fullKillEffects,
                enabled
            ) {
                onUpdate { p -> p.copy(fullKillEffects = it) }
            }
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CurrencyCheckRow(
    label: String,
    checked: Boolean,
    value: Long,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    onValue: (Long) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).clickable(enabled = enabled) { onChecked(!checked) }
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.toLongOrNull()?.let { onValue(it.coerceAtLeast(0L)) }
            },
            enabled = enabled && checked,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(135.dp)
        )
    }
}

@Composable
private fun CloudSafetyAuditCard(plan: QuickModPlan, lang: AppLanguage) {
    val report = remember(plan, lang) { plan.evaluateCloudSafety(lang) }
    val containerColor = when {
        report.hasHighRisk -> MaterialTheme.colorScheme.errorContainer
        report.mediumWarnings.isNotEmpty() -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        report.hasHighRisk -> MaterialTheme.colorScheme.onErrorContainer
        report.mediumWarnings.isNotEmpty() -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (report.hasHighRisk) Icons.Default.Warning else Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (report.hasHighRisk) {
                        bi("Kiểm tra Cloud: Có mục rủi ro cao!", "Cloud Audit: High-risk option selected!", lang)
                    } else {
                        bi("Kiểm tra toàn vẹn cấu trúc Cloud Save", "Cloud Save Structural Integrity Audit", lang)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
            }
            report.highRiskWarnings.forEach { w ->
                Text("⛔ $w", style = MaterialTheme.typography.bodySmall, color = contentColor)
            }
            report.mediumWarnings.forEach { w ->
                Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = contentColor)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            report.protections.forEach { p ->
                Text("✓ $p", style = MaterialTheme.typography.labelSmall, color = contentColor)
            }
        }
    }
}
