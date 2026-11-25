package com.dmedit.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmedit.app.ui.theme.DmeditTheme
import com.dmedit.core.shell.AppLanguage
import com.dmedit.core.shell.AppLocaleHolder
import com.dmedit.core.shell.SettingsRepository
import com.dmedit.core.shell.bi
import com.dmedit.feature.backups.BackupsScreen
import com.dmedit.feature.editor.DualEditorScreen
import com.dmedit.feature.editor.QuickModScreen
import com.dmedit.feature.explorer.ExplorerScreen
import com.dmedit.feature.settings.SettingsScreen
import com.dmedit.feature.spoofer.SpooferScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private enum class MainTab(val labelVi: String, val labelEn: String, val icon: ImageVector) {
    QUICK_MOD("Sửa Nhanh", "Quick Mod", Icons.Default.Bolt),
    FILES("Tệp Save", "Save Files", Icons.Default.Folder),
    BACKUPS("Backup", "Backups", Icons.Default.Archive),
    SPOOFER("Spoofer", "Spoofer", Icons.Default.Security),
    SETTINGS("Cài đặt", "Settings", Icons.Default.Settings);

    fun label(lang: AppLanguage): String = bi(labelVi, labelEn, lang)
}

private data class EditorTarget(
    val filePath: String,
    val profileId: String,
    val packageName: String,
    val archivePath: String? = null,
    val archiveEntryPath: String? = null
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllFilesAccessIfNeeded()

        val incomingUri = if (intent?.action == Intent.ACTION_VIEW) intent.data else null
        val initialTab = if (incomingUri != null) MainTab.BACKUPS else MainTab.QUICK_MOD

        setContent {
            DmeditTheme {
                LaunchedEffect(Unit) {
                    settingsRepository.settings.collect { }
                }
                val lang by AppLocaleHolder.language.collectAsStateWithLifecycle()
                val windowSizeClass = calculateWindowSizeClass(this)
                val isWideScreen = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded ||
                    windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium

                Surface(modifier = Modifier.fillMaxSize()) {
                    DmeditRootApp(
                        initialTab = initialTab,
                        initialIncomingBackupUri = incomingUri,
                        isWideScreen = isWideScreen,
                        lang = lang
                    )
                }
            }
        }
    }

    private fun requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }
}

@Composable
private fun DmeditRootApp(
    initialTab: MainTab,
    initialIncomingBackupUri: Uri?,
    isWideScreen: Boolean,
    lang: AppLanguage
) {
    var activeTab by rememberSaveable { mutableStateOf(initialTab) }
    var pendingBackupUri by remember { mutableStateOf(initialIncomingBackupUri) }
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }

    BackHandler(enabled = editorTarget != null || activeTab != MainTab.QUICK_MOD) {
        if (editorTarget != null) {
            editorTarget = null
        } else {
            activeTab = MainTab.QUICK_MOD
        }
    }

    val openTarget = editorTarget
    if (openTarget != null) {
        DualEditorScreen(
            filePath = openTarget.filePath,
            profileId = openTarget.profileId,
            packageName = openTarget.packageName,
            isWideScreen = isWideScreen,
            archivePath = openTarget.archivePath,
            archiveEntryPath = openTarget.archiveEntryPath,
            onNavigateBack = { editorTarget = null }
        )
        return
    }

    if (isWideScreen) {
        // Landscape / LDPlayer Desktop-like wide layout using NavigationRail
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                MainTab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label(lang)) },
                        label = { Text(tab.label(lang)) }
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                TabContent(
                    activeTab = activeTab,
                    isWideScreen = isWideScreen,
                    incomingBackupUri = pendingBackupUri,
                    onIncomingUriConsumed = { pendingBackupUri = null },
                    onOpenEditor = { editorTarget = it }
                )
            }
        }
    } else {
        // Portrait / Mobile compact layout using bottom NavigationBar
        Scaffold(
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label(lang)) },
                            label = { Text(tab.label(lang)) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                TabContent(
                    activeTab = activeTab,
                    isWideScreen = isWideScreen,
                    incomingBackupUri = pendingBackupUri,
                    onIncomingUriConsumed = { pendingBackupUri = null },
                    onOpenEditor = { editorTarget = it }
                )
            }
        }
    }
}

@Composable
private fun TabContent(
    activeTab: MainTab,
    isWideScreen: Boolean,
    incomingBackupUri: Uri?,
    onIncomingUriConsumed: () -> Unit,
    onOpenEditor: (EditorTarget) -> Unit
) {
    when (activeTab) {
        MainTab.QUICK_MOD -> QuickModScreen(isWideScreen = isWideScreen)
        MainTab.FILES -> ExplorerScreen(
            onOpenFileForEdit = { filePath, profileId, packageName ->
                onOpenEditor(EditorTarget(filePath, profileId, packageName))
            }
        )
        MainTab.BACKUPS -> BackupsScreen(
            incomingBackupUri = incomingBackupUri,
            onIncomingUriConsumed = onIncomingUriConsumed,
            onEditInsideArchive = { archivePath, entryPath, profileId, packageName ->
                onOpenEditor(
                    EditorTarget(
                        filePath = entryPath,
                        profileId = profileId,
                        packageName = packageName,
                        archivePath = archivePath,
                        archiveEntryPath = entryPath
                    )
                )
            }
        )
        MainTab.SPOOFER -> SpooferScreen()
        MainTab.SETTINGS -> SettingsScreen()
    }
}
