package com.dmedit.feature.backups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmedit.core.model.BackupArchiveInfo
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.bi

@Composable
fun BackupsScreen(
    incomingBackupUri: android.net.Uri? = null,
    onIncomingUriConsumed: () -> Unit = {},
    onEditInsideArchive: (archivePath: String, entryPath: String, profileId: String, packageName: String) -> Unit,
    viewModel: BackupsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lang by AppLocaleHolder.language.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var inspectTarget by remember { mutableStateOf<BackupArchiveInfo?>(null) }

    androidx.compose.runtime.LaunchedEffect(incomingBackupUri) {
        if (incomingBackupUri != null) {
            viewModel.importBackupUri(incomingBackupUri)
            onIncomingUriConsumed()
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importBackupUri)
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = bi("Tạo backup .skdb", "Create .skdb backup", lang))
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(bi("Quản lý Sao lưu (.skdb v2)", "Backup Manager (.skdb v2)", lang), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${state.activeProfileName} • ${state.backupsDirPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Text(bi(" Nhập .skdb", " Import .skdb", lang))
                }
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = bi("Làm mới", "Refresh", lang))
                }
            }

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.backups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        bi(
                            "Chưa có bản sao lưu .skdb nào. Nhấn '+' để tạo backup mới.",
                            "No .skdb backups yet. Tap '+' to create a new backup.",
                            lang
                        )
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.backups, key = { it.filePath }) { archive ->
                        ListItem(
                            modifier = Modifier.clickable { inspectTarget = archive },
                            leadingContent = {
                                Icon(Icons.Default.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            headlineContent = { Text(archive.fileName) },
                            supportingContent = {
                                val badge = if (archive.isLegacySkdb) "SKDB Legacy" else "SKDB v2 (SHA-256)"
                                val sanitized = if (archive.manifest?.sanitized == true) bi(" • Đã xóa Account ID", " • Account ID Stripped", lang) else ""
                                Text("$badge • ${archive.entryNames.size} ${bi("tệp", "files", lang)} • ${archive.sizeBytes / 1024} KB$sanitized")
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { inspectTarget = archive }) {
                                        Icon(Icons.Default.Restore, contentDescription = bi("Khôi phục / Sửa", "Restore / Edit", lang))
                                    }
                                    IconButton(onClick = { viewModel.exportToSharedFolder(archive) }) {
                                        Icon(Icons.Default.FolderShared, contentDescription = bi("Xuất sang thư mục chia sẻ", "Export to Shared Folder", lang))
                                    }
                                    IconButton(onClick = { viewModel.deleteBackup(archive) }) {
                                        Icon(Icons.Default.Delete, contentDescription = bi("Xóa", "Delete", lang))
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var includePrefs by remember { mutableStateOf(state.hasSharedPrefs) }
        var sanitizeAccount by remember { mutableStateOf(true) }
        var selectedFiles by remember(state.availableGameFiles) {
            mutableStateOf(state.availableGameFiles.filter { it.isGameRelevant }.map { it.name }.toSet())
        }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(bi("Tạo bản sao lưu .skdb v2", "Create .skdb v2 Backup", lang)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(bi("Tên backup (để trống = tự đặt theo giờ)", "Backup name (blank = auto timestamp)", lang)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(bi("Tự động xóa Account / Cloud ID", "Strip Account / Cloud ID automatically", lang), modifier = Modifier.weight(1f))
                        Switch(checked = sanitizeAccount, onCheckedChange = { sanitizeAccount = it })
                    }
                    if (state.hasSharedPrefs) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = includePrefs, onCheckedChange = { includePrefs = it })
                            Text(bi("Bao gồm playerprefs.xml", "Include playerprefs.xml", lang))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(state.availableGameFiles, key = { it.name }) { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = entry.name in selectedFiles,
                                    onCheckedChange = { checked ->
                                        selectedFiles = if (checked) selectedFiles + entry.name else selectedFiles - entry.name
                                    }
                                )
                                Text(entry.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createBackup(name, selectedFiles, includePrefs, sanitizeAccount)
                    showCreateDialog = false
                }) { Text(bi("Tạo Backup", "Create Backup", lang)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(bi("Hủy", "Cancel", lang)) }
            }
        )
    }

    inspectTarget?.let { archive ->
        var selectedEntries by remember(archive) { mutableStateOf(archive.entryNames.toSet()) }
        val profileId = archive.manifest?.gameProfileId ?: state.activeProfileId
        val pkgName = archive.manifest?.packageName ?: state.activePackageName

        AlertDialog(
            onDismissRequest = { inspectTarget = null },
            title = { Text(archive.fileName) },
            text = {
                Column {
                    Text(
                        text = bi(
                            "Chọn các tệp muốn khôi phục hoặc nhấn biểu tượng bút để chỉnh sửa trực tiếp bên trong .skdb:",
                            "Select files to restore or tap the edit icon to edit directly inside the .skdb archive:",
                            lang
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        items(archive.entryNames) { entryPath ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = entryPath in selectedEntries,
                                    onCheckedChange = { checked ->
                                        selectedEntries = if (checked) selectedEntries + entryPath else selectedEntries - entryPath
                                    }
                                )
                                Text(
                                    text = entryPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    inspectTarget = null
                                    onEditInsideArchive(archive.filePath, entryPath, profileId, pkgName)
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = bi("Sửa trực tiếp trong .skdb", "Edit inside .skdb", lang))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreBackup(archive, selectedEntries)
                    inspectTarget = null
                }) { Text(bi("Khôi phục đã chọn", "Restore Selected", lang)) }
            },
            dismissButton = {
                TextButton(onClick = { inspectTarget = null }) { Text(bi("Đóng", "Close", lang)) }
            }
        )
    }
}
