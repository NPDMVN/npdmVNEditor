package com.dmedit.feature.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmedit.core.model.SaveFileEntry
import com.dmedit.core.shell.AppLanguage
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.bi

@Composable
fun ExplorerScreen(
    onOpenFileForEdit: (filePath: String, profileId: String, packageName: String) -> Unit,
    viewModel: ExplorerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lang by AppLocaleHolder.language.collectAsStateWithLifecycle()
    var showProfileMenu by remember { mutableStateOf(false) }
    var showPackagePicker by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SaveFileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<SaveFileEntry?>(null) }
    var defaultShardWarningTarget by remember { mutableStateOf<SaveFileEntry?>(null) }

    val detectedUidList = remember(state.entries) {
        state.entries
            .filter { !it.stat.isDirectory }
            .mapNotNull { UID_SHARD_REGEX.matchEntire(it.name)?.groupValues?.getOrNull(1) }
            .distinct()
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Header Row: Profile Picker + Quick Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                AssistChip(
                    onClick = { showProfileMenu = true },
                    label = {
                        Text(
                            text = state.activeBundle?.profile?.displayName ?: bi("Chọn Game Profile", "Select Game Profile", lang),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.SportsEsports, contentDescription = null) }
                )
                DropdownMenu(
                    expanded = showProfileMenu,
                    onDismissRequest = { showProfileMenu = false }
                ) {
                    state.profiles.forEach { bundle ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(bundle.profile.displayName)
                                    Text(
                                        text = bundle.profile.packages.firstOrNull() ?: bi("Đa game Unity", "Multi-Unity Game", lang),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                showProfileMenu = false
                                viewModel.selectProfile(bundle)
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(bi("Tìm tệp save...", "Search save files...", lang)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )

            IconButton(onClick = { viewModel.refreshAll(forceReprobe = true) }) {
                Icon(Icons.Default.Refresh, contentDescription = bi("Làm mới", "Refresh", lang))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Path Chips (Optimized for LDPlayer workflow)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.filterGameFiles,
                    onClick = viewModel::toggleFilterGameFiles,
                    label = { Text(bi("Lọc file game", "Game Files Only", lang)) },
                    leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) }
                )
            }
            item {
                AssistChip(
                    onClick = { showPackagePicker = true },
                    label = {
                        Text(
                            text = state.activePackage.ifBlank { bi("Chọn gói game", "Select Package", lang) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.SportsEsports, contentDescription = null) }
                )
            }
            item {
                AssistChip(
                    onClick = viewModel::jumpToGameFilesDir,
                    label = { Text(bi("Save Game (/files)", "Game Saves (/files)", lang)) },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) }
                )
            }
            item {
                AssistChip(
                    onClick = viewModel::jumpToSharedPrefsDir,
                    label = { Text("PlayerPrefs XML") },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }
                )
            }
            item {
                AssistChip(
                    onClick = viewModel::jumpToSharedEmulatorFolder,
                    label = { Text(bi("Thư mục ${state.rootInfo.emulatorType.displayName}", "${state.rootInfo.emulatorType.displayName} Shared", lang)) },
                    leadingIcon = { Icon(Icons.Default.FolderShared, contentDescription = null) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Root / Emulator Diagnostic Banner when Root is missing
        if (!state.rootInfo.hasRoot) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.rootInfo.diagnosticMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Current Path Breadcrumb
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = viewModel::navigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Thư mục cha")
                }
                Text(
                    text = state.currentDirPath.ifBlank { "/" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${state.rootInfo.emulatorType.displayName} • ${state.rootInfo.kernelArch}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        state.errorMessage?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        state.statusMessage?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // File List
        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.visibleEntries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = bi("Không có tệp nào phù hợp trong thư mục này.", "No matching files in this directory.", lang),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.visibleEntries, key = { it.fullPath }) { entry ->
                        val isDefaultShadowShard = detectedUidList.isNotEmpty() &&
                            !entry.stat.isDirectory &&
                            isDefaultNoUidSoulKnightShard(entry.name)
                        FileEntryRow(
                            entry = entry,
                            lang = lang,
                            isDefaultShadowShard = isDefaultShadowShard,
                            onClick = {
                                if (entry.stat.isDirectory) {
                                    viewModel.openDirectory(entry.fullPath)
                                } else if (isDefaultShadowShard) {
                                    defaultShardWarningTarget = entry
                                } else {
                                    val profileId = state.activeBundle?.profile?.id ?: return@FileEntryRow
                                    onOpenFileForEdit(entry.fullPath, profileId, state.activePackage)
                                }
                            },
                            onRename = { renameTarget = entry },
                            onDelete = { deleteTarget = entry }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var newName by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(bi("Đổi tên tệp", "Rename File", lang)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(bi("Tên mới", "New Name", lang)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameEntry(target, newName.trim())
                    renameTarget = null
                }) { Text(bi("Lưu", "Save", lang)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(bi("Hủy", "Cancel", lang)) }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(bi("Xác nhận xóa", "Confirm Delete", lang)) },
            text = { Text(bi("Bạn chắc chắn muốn xóa '${target.name}'?", "Are you sure you want to delete '${target.name}'?", lang)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(target)
                    deleteTarget = null
                }) { Text(bi("Xóa", "Delete", lang), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(bi("Hủy", "Cancel", lang)) }
            }
        )
    }

    defaultShardWarningTarget?.let { target ->
        val uidText = detectedUidList.joinToString(", ")
        AlertDialog(
            onDismissRequest = { defaultShardWarningTarget = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(bi("Cảnh báo: Tệp data mặc định (không UID)", "Warning: Default Save File (no UID)", lang)) },
            text = {
                Text(
                    bi(
                        "Tệp '${target.name}' là tệp mặc định (khách) KHÔNG có mã UID, trong khi thư mục này đã có save tài khoản UID ($uidText).\n\n" +
                            "Sửa tệp mặc định sẽ KHÔNG áp dụng vào tài khoản đang đăng nhập và dễ gây lệch dữ liệu / bị hệ thống phát hiện.\n\n" +
                            "Khuyến nghị: Đóng hộp thoại này và mở tệp có đuôi _${detectedUidList.firstOrNull().orEmpty()}_.data.new hoặc dùng tab 'Sửa Nhanh'!",
                        "'${target.name}' is a guest default file WITHOUT an Account UID, while this folder already has UID save(s): $uidText.\n\n" +
                            "Editing default files will NOT apply to your logged-in account and can trigger mismatches or bans.\n\n" +
                            "Recommended: Close this dialog and open the _${detectedUidList.firstOrNull().orEmpty()}_.data.new file or use the 'Quick Mod' tab!",
                        lang
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { defaultShardWarningTarget = null }) {
                    Text(bi("Đóng (Khuyên dùng)", "Close (Recommended)", lang))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val profileId = state.activeBundle?.profile?.id
                    defaultShardWarningTarget = null
                    if (profileId != null) {
                        onOpenFileForEdit(target.fullPath, profileId, state.activePackage)
                    }
                }) { Text(bi("Vẫn mở", "Open Anyway", lang), color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    if (showPackagePicker) {
        var pkgFilter by remember { mutableStateOf("") }
        val filteredPkgs = remember(state.installedPackages, pkgFilter) {
            state.installedPackages.filter { pkgFilter.isBlank() || it.contains(pkgFilter, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showPackagePicker = false },
            title = { Text(bi("Chọn gói game đã cài trên giả lập", "Select Installed Package", lang)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pkgFilter,
                        onValueChange = { pkgFilter = it },
                        singleLine = true,
                        placeholder = { Text(bi("Tìm package (VD: chillyroom, unity...)...", "Filter packages (e.g. chillyroom, unity)...", lang)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(240.dp)) {
                        items(filteredPkgs, key = { it }) { pkg ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    state.activeBundle?.let { bundle ->
                                        viewModel.selectProfile(bundle, packageOverride = pkg)
                                    }
                                    showPackagePicker = false
                                },
                                headlineContent = { Text(pkg, style = MaterialTheme.typography.bodySmall) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPackagePicker = false }) { Text(bi("Đóng", "Close", lang)) }
            }
        )
    }
}

@Composable
private fun FileEntryRow(
    entry: SaveFileEntry,
    lang: AppLanguage,
    isDefaultShadowShard: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = when {
                    entry.stat.isDirectory -> Icons.Default.Folder
                    isDefaultShadowShard -> Icons.Default.Warning
                    entry.codecId != "plain" -> Icons.Default.Lock
                    entry.format.name == "JSON" || entry.format.name == "XML" -> Icons.Default.Code
                    else -> Icons.Default.Description
                },
                contentDescription = null,
                tint = when {
                    entry.stat.isDirectory -> MaterialTheme.colorScheme.primary
                    isDefaultShadowShard -> MaterialTheme.colorScheme.error
                    entry.codecId != "plain" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        headlineContent = {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isDefaultShadowShard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            val meta = buildString {
                if (isDefaultShadowShard) {
                    append(bi("⚠ DATA MẶC ĐỊNH (không UID — tránh sửa!) • ", "⚠ DEFAULT DATA (no UID — avoid editing!) • ", lang))
                }
                if (entry.stat.isDirectory) {
                    append(bi("Thư mục", "Folder", lang))
                } else {
                    append(formatFileSize(entry.stat.sizeBytes))
                    append(" • ")
                    append(entry.codecId.uppercase())
                    entry.matchedRule?.label?.let { append(" • $it") }
                    if (entry.visualSchemaPath != null) append(" • Visual Form")
                }
                append(" • ${entry.stat.modeOctal}")
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDefaultShadowShard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = bi("Tùy chọn", "Options", lang))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(bi("Đổi tên", "Rename", lang)) },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(bi("Xóa", "Delete", lang)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    )
}

private val UID_SHARD_REGEX =
    Regex("^[a-z0-9_]+_([0-9]+|[0-9a-f]{16})_\\.data(?:\\.(?:new|rij))?$", RegexOption.IGNORE_CASE)

private val DEFAULT_NO_UID_SHARD_REGEX = Regex(
    "^(?:(?:item_data|season_data|escape_season_data|escape_run_save|statistic|weapon_evolution_data|misc_data|monsrise_data|bp_data|task|pvp_data|setting|mall_reload_data)\\.data(?:\\.(?:new|rij))?|[a-z0-9_]+_LOCAL\\.data(?:\\.(?:new|rij))?)$",
    RegexOption.IGNORE_CASE
)

private fun isDefaultNoUidSoulKnightShard(fileName: String): Boolean =
    DEFAULT_NO_UID_SHARD_REGEX.matches(fileName)

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
}
