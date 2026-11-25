package com.dmedit.core.shell

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dmedit.core.model.AppSettings
import com.dmedit.core.model.StorageMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "dmedit_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val ACTIVE_PACKAGE_OVERRIDE = stringPreferencesKey("active_package_override")
        val FILTER_GAME_FILES = booleanPreferencesKey("filter_game_files")
        val FORCE_STOP_BEFORE_WRITE = booleanPreferencesKey("force_stop_before_write")
        val AUTO_BACKUP_BEFORE_SAVE = booleanPreferencesKey("auto_backup_before_save")
        val SHOW_DIFF_BEFORE_SAVE = booleanPreferencesKey("show_diff_before_save")
        val USE_CUSTOM_TOYBOX = booleanPreferencesKey("use_custom_toybox")
        val CUSTOM_TOYBOX_CMD = stringPreferencesKey("custom_toybox_cmd")
        val STORAGE_MODE = stringPreferencesKey("storage_mode")
        val DARK_THEME_MODE = stringPreferencesKey("dark_theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val langCode = prefs[Keys.APP_LANGUAGE] ?: "vi"
        AppLocaleHolder.updateFromCode(langCode)
        AppSettings(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID] ?: "chillyroom.soul_knight",
            activePackageOverride = prefs[Keys.ACTIVE_PACKAGE_OVERRIDE]?.ifBlank { null },
            filterGameFiles = prefs[Keys.FILTER_GAME_FILES] ?: true,
            forceStopGameBeforeWrite = prefs[Keys.FORCE_STOP_BEFORE_WRITE] ?: true,
            autoBackupBeforeSave = prefs[Keys.AUTO_BACKUP_BEFORE_SAVE] ?: true,
            showDiffBeforeSave = prefs[Keys.SHOW_DIFF_BEFORE_SAVE] ?: true,
            useCustomToybox = prefs[Keys.USE_CUSTOM_TOYBOX] ?: false,
            customToyboxCommand = prefs[Keys.CUSTOM_TOYBOX_CMD] ?: "",
            storageMode = runCatching {
                StorageMode.valueOf(prefs[Keys.STORAGE_MODE] ?: StorageMode.ROOT.name)
            }.getOrDefault(StorageMode.ROOT),
            darkThemeMode = prefs[Keys.DARK_THEME_MODE] ?: "system",
            language = langCode
        )
    }

    suspend fun setActiveProfile(profileId: String, packageOverride: String? = null) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_PROFILE_ID] = profileId
            prefs[Keys.ACTIVE_PACKAGE_OVERRIDE] = packageOverride.orEmpty()
        }
    }

    suspend fun setFilterGameFiles(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FILTER_GAME_FILES] = enabled }
    }

    suspend fun setForceStopBeforeWrite(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FORCE_STOP_BEFORE_WRITE] = enabled }
    }

    suspend fun setAutoBackupBeforeSave(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_BACKUP_BEFORE_SAVE] = enabled }
    }

    suspend fun setShowDiffBeforeSave(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_DIFF_BEFORE_SAVE] = enabled }
    }

    suspend fun setCustomToybox(enabled: Boolean, command: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.USE_CUSTOM_TOYBOX] = enabled
            prefs[Keys.CUSTOM_TOYBOX_CMD] = command
        }
    }

    suspend fun setStorageMode(mode: StorageMode) {
        context.settingsDataStore.edit { it[Keys.STORAGE_MODE] = mode.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        AppLocaleHolder.update(language)
        context.settingsDataStore.edit { it[Keys.APP_LANGUAGE] = language.code }
    }
}
