package com.dmedit.core.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage(val code: String, val nativeName: String, val shortBadge: String) {
    VI("vi", "Tiếng Việt", "VI"),
    EN("en", "English", "EN");

    fun toggled(): AppLanguage = if (this == VI) EN else VI

    companion object {
        fun fromCode(code: String?): AppLanguage =
            if (code.equals("en", ignoreCase = true)) EN else VI
    }
}

object AppLocaleHolder {
    private val _language = MutableStateFlow(AppLanguage.VI)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    val current: AppLanguage get() = _language.value
    val isEn: Boolean get() = _language.value == AppLanguage.EN

    fun update(lang: AppLanguage) {
        _language.value = lang
    }

    fun updateFromCode(code: String?) {
        _language.value = AppLanguage.fromCode(code)
    }
}

fun bi(vi: String, en: String, lang: AppLanguage = AppLocaleHolder.current): String =
    if (lang == AppLanguage.EN) en else vi
