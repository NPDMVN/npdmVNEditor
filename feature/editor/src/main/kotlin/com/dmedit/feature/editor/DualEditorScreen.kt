package com.dmedit.feature.editor

import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmedit.core.model.ChangeType
import com.dmedit.core.model.DataFormat
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.bi
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualEditorScreen(
    filePath: String,
    profileId: String,
    packageName: String,
    isWideScreen: Boolean,
    archivePath: String? = null,
    archiveEntryPath: String? = null,
    onNavigateBack: () -> Unit,
    viewModel: DualEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lang by AppLocaleHolder.language.collectAsStateWithLifecycle()
    var soraRef by remember { mutableStateOf<CodeEditor?>(null) }

    LaunchedEffect(filePath, profileId, archivePath, archiveEntryPath) {
        viewModel.openFile(
            filePath = filePath,
            profileId = profileId,
            packageName = packageName,
            isWideScreen = isWideScreen,
            archivePath = archivePath,
            archiveEntryPath = archiveEntryPath
        )
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isCtrlPressed) {
                when (keyEvent.key) {
                    Key.S -> { viewModel.requestSave(); true }
                    Key.F -> { viewModel.toggleFindReplace(); true }
                    Key.Z -> { viewModel.undo(); true }
                    Key.Y -> { viewModel.redo(); true }
                    else -> false
                }
            } else {
                false
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = bi("Quay lại", "Back", lang))
                    }
                },
                title = {
                    Column {
                        Text(
                            text = (if (state.isDirty) "• " else "") + state.fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${state.codecId.uppercase()} • ${state.format.name}" +
                                (if (state.archivePath != null) bi(" • Trong .skdb", " • Inside .skdb", lang) else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (state.format == DataFormat.JSON) {
                        val modes = if (isWideScreen) {
                            listOf(EditorDisplayMode.VISUAL, EditorDisplayMode.SPLIT, EditorDisplayMode.RAW)
                        } else {
                            listOf(EditorDisplayMode.VISUAL, EditorDisplayMode.RAW)
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(end = 6.dp)) {
                            modes.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = state.displayMode == mode,
                                    onClick = { viewModel.setDisplayMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                                ) {
                                    Text(
                                        text = when (mode) {
                                            EditorDisplayMode.VISUAL -> "Visual"
                                            EditorDisplayMode.SPLIT -> bi("2 khung", "Split", lang)
                                            EditorDisplayMode.RAW -> "Raw"
                                        },
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    IconButton(onClick = viewModel::undo, enabled = state.canUndo && !state.isSaving) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Hoàn tác (Ctrl+Z)")
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo && !state.isSaving) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Làm lại (Ctrl+Y)")
                    }
                    IconButton(onClick = viewModel::toggleFindReplace) {
                        Icon(Icons.Default.Search, contentDescription = "Tìm & thay thế (Ctrl+F)")
                    }
                    if (state.format == DataFormat.JSON) {
                        IconButton(onClick = viewModel::beautifyJson, enabled = !state.isSaving) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "Định dạng JSON")
                        }
                    }
                    IconButton(onClick = viewModel::reloadFromDisk, enabled = !state.isLoading && !state.isSaving) {
                        Icon(Icons.Default.Refresh, contentDescription = "Tải lại từ đĩa")
                    }
                    if (state.lastRollbackPath != null) {
                        IconButton(onClick = viewModel::rollbackLastSave, enabled = !state.isSaving) {
                            Icon(Icons.Default.History, contentDescription = "Rollback bản gốc")
                        }
                    }
                    IconButton(onClick = viewModel::requestSave, enabled = !state.isSaving && !state.isLoading) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Lưu (Ctrl+S)",
                            tint = if (state.isDirty) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.isSaving || state.isRepairingFormat) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.errorMessage?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::dismissErrorMessage) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Đóng lỗi",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            state.statusMessage?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::dismissStatusMessage) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Đóng thông báo",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            state.newFormatConflict?.takeIf { it.hasBannerToShow }?.let { conflict ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            if (conflict.hasMovedBackupToRestore) {
                                Text(
                                    text = "Tìm thấy ${conflict.movedBackupCount} tệp .data.new đã sao lưu trước đó. dmedit nay đã giải mã native .data.new (AES-256) và tự đồng bộ game.data ↔ playerprefs.xml khi bấm Lưu!",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (conflict.hasRijConflict) {
                                Text(
                                    text = "Bạn đang mở bản cũ (${state.fileName}) trong khi có bản .data.new mới hơn — hãy mở trực tiếp tệp .data.new để chỉnh (dmedit hỗ trợ AES-256 native) hoặc bấm 'Tắt .data.new'.",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (conflict.siblingGameDataFiles.isNotEmpty()) {
                                Text(
                                    text = "Lưu ý: thư mục save còn có tệp theo tài khoản (${conflict.siblingGameDataFiles.joinToString(", ")}).",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (conflict.hasMovedBackupToRestore) {
                            Button(
                                onClick = viewModel::restoreMovedNewFormat,
                                enabled = !state.isRepairingFormat && !state.isSaving
                            ) {
                                Text("Khôi phục .data.new")
                            }
                        } else if (conflict.hasRijConflict) {
                            Button(
                                onClick = viewModel::switchToClassicSaveFormat,
                                enabled = !state.isRepairingFormat && !state.isSaving
                            ) {
                                Text("Tắt .data.new")
                            }
                        }
                    }
                }
            }

            if (state.showFindReplace && state.displayMode != EditorDisplayMode.VISUAL) {
                FindReplaceBar(
                    editor = soraRef,
                    onClose = viewModel::toggleFindReplace
                )
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                when (state.displayMode) {
                    EditorDisplayMode.VISUAL -> VisualFormEditorPane(
                        jsonRoot = state.jsonRoot,
                        schema = state.visualSchema,
                        catalog = state.itemCatalog,
                        isModernFormat = state.isModernFormatFile,
                        availablePresetKeys = state.availablePresetKeys,
                        onPatchField = viewModel::onVisualPatchField,
                        onPatchMapEntry = viewModel::onVisualPatchMapEntry,
                        onBatchMap = viewModel::onVisualBatchMap,
                        onBatchNestedPairs = viewModel::onVisualBatchNestedPairs,
                        onApplyPresetMerge = viewModel::onApplyPresetMerge,
                        modifier = Modifier.fillMaxSize()
                    )

                    EditorDisplayMode.RAW -> SoraCodeEditorPane(
                        text = state.rawText,
                        revision = state.revision,
                        onTextChanged = viewModel::onRawTextChanged,
                        onEditorCreated = { soraRef = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    EditorDisplayMode.SPLIT -> Row(modifier = Modifier.fillMaxSize()) {
                        VisualFormEditorPane(
                            jsonRoot = state.jsonRoot,
                            schema = state.visualSchema,
                            catalog = state.itemCatalog,
                            isModernFormat = state.isModernFormatFile,
                            availablePresetKeys = state.availablePresetKeys,
                            onPatchField = viewModel::onVisualPatchField,
                            onPatchMapEntry = viewModel::onVisualPatchMapEntry,
                            onBatchMap = viewModel::onVisualBatchMap,
                            onBatchNestedPairs = viewModel::onVisualBatchNestedPairs,
                            onApplyPresetMerge = viewModel::onApplyPresetMerge,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        VerticalDivider()
                        SoraCodeEditorPane(
                            text = state.rawText,
                            revision = state.revision,
                            onTextChanged = viewModel::onRawTextChanged,
                            onEditorCreated = { soraRef = it },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }

    if (state.showDiffDialog) {
        val visibleDiffs = remember(state.pendingDiffs) { state.pendingDiffs.take(100) }
        val hiddenCount = (state.pendingDiffs.size - visibleDiffs.size).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = viewModel::dismissDiffDialog,
            title = { Text("Kiểm tra thay đổi trước khi lưu (${state.pendingDiffs.size})") },
            text = {
                if (state.pendingDiffs.isEmpty()) {
                    Text("Không có thay đổi nào so với bản gốc. Bạn vẫn muốn ghi lại tệp?")
                } else {
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(visibleDiffs) { diff ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "${diff.changeType.name}: ${diff.path}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = when (diff.changeType) {
                                        ChangeType.ADDED -> MaterialTheme.colorScheme.primary
                                        ChangeType.REMOVED -> MaterialTheme.colorScheme.error
                                        ChangeType.MODIFIED -> MaterialTheme.colorScheme.tertiary
                                    }
                                )
                                diff.oldValue?.let {
                                    Text("- $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                                diff.newValue?.let {
                                    Text("+ $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                            HorizontalDivider()
                        }
                        if (hiddenCount > 0) {
                            item {
                                Text(
                                    text = "... và còn $hiddenCount thay đổi khác.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::commitSave) { Text("Mã hóa & Lưu") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDiffDialog) { Text("Hủy") }
            }
        )
    }
}

@Composable
private fun SoraCodeEditorPane(
    text: String,
    revision: Long,
    onTextChanged: (String) -> Unit,
    onEditorCreated: (CodeEditor) -> Unit,
    modifier: Modifier = Modifier
) {
    val isProgrammaticUpdate = remember { mutableStateOf(false) }
    val lastAppliedRevision = remember { mutableStateOf(-1L) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(13f)
                colorScheme = SchemeDarcula()
                isWordwrap = false
                isLineNumberEnabled = true
                setText(text)
                lastAppliedRevision.value = revision
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    if (!isProgrammaticUpdate.value) {
                        onTextChanged(this.text.toString())
                    }
                }
                onEditorCreated(this)
            }
        },
        update = { editor ->
            if (lastAppliedRevision.value != revision && editor.text.toString() != text) {
                isProgrammaticUpdate.value = true
                editor.setText(text)
                lastAppliedRevision.value = revision
                isProgrammaticUpdate.value = false
            }
        }
    )
}

@Composable
private fun FindReplaceBar(
    editor: CodeEditor?,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var useRegex by remember { mutableStateOf(false) }

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { q ->
                        query = q
                        val searcher = editor?.searcher
                        if (searcher != null) {
                            if (q.isEmpty()) {
                                searcher.stopSearch()
                            } else {
                                runCatching {
                                    searcher.search(
                                        q,
                                        EditorSearcher.SearchOptions(!matchCase, useRegex)
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    placeholder = { Text("Tìm...") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    singleLine = true,
                    placeholder = { Text("Thay bằng...") },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { runCatching { editor?.searcher?.gotoPrevious() } }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Trước")
                }
                IconButton(onClick = { runCatching { editor?.searcher?.gotoNext() } }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Tiếp")
                }
                TextButton(onClick = {
                    runCatching { editor?.searcher?.replaceAll(replaceText) }
                }) { Text("Thay tất cả") }
                IconButton(onClick = {
                    editor?.searcher?.stopSearch()
                    onClose()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Đóng")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = matchCase, onCheckedChange = { matchCase = it })
                Text("Phân biệt hoa thường", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(12.dp))
                Checkbox(checked = useRegex, onCheckedChange = { useRegex = it })
                Text("Regex", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
