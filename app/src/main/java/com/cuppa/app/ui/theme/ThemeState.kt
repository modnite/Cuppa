package com.cuppa.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ThemeState — Reactive state holder for app theme (System/Light/Dark & Dynamic Color).
 */
object ThemeState {
    private const val PREFS_NAME = "cuppa_settings"
    private const val KEY_DARK_MODE = "theme_dark_mode" // 0=system, 1=light, 2=dark
    private const val KEY_DYNAMIC_COLOR = "theme_dynamic_color"

    private val _darkMode = MutableStateFlow(0)
    val darkMode: StateFlow<Int> = _darkMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(true)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _darkMode.value = prefs.getInt(KEY_DARK_MODE, 0)
        _dynamicColor.value = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
    }

    fun setDarkMode(context: Context, mode: Int) {
        _darkMode.value = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DARK_MODE, mode).apply()
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        _dynamicColor.value = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }
}
