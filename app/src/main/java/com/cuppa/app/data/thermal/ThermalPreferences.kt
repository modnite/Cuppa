package com.cuppa.app.data.thermal

import android.content.Context
import android.content.SharedPreferences
import com.cuppa.cups.thermal.ThermalRasterizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThermalSettingsState(
    val defaultLabelSize: String = "4x6",
    val darkness: Int = 15,
    val speedIps: Int = 4,
    val ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG,
    val invertPolarity: Boolean = false
)

/**
 * ThermalPreferences — Persistent settings for thermal printheads and dithering.
 */
class ThermalPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "cuppa_thermal_prefs"
        private const val KEY_LABEL_SIZE = "label_size"
        private const val KEY_DARKNESS = "darkness"
        private const val KEY_SPEED = "speed_ips"
        private const val KEY_DITHER = "dither_mode"
        private const val KEY_INVERT_POLARITY = "invert_polarity"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ThermalSettingsState> = _settings.asStateFlow()

    private fun loadSettings(): ThermalSettingsState {
        val size = prefs.getString(KEY_LABEL_SIZE, "4x6") ?: "4x6"
        val darkness = prefs.getInt(KEY_DARKNESS, 15)
        val speed = prefs.getInt(KEY_SPEED, 4)
        val ditherStr = prefs.getString(KEY_DITHER, ThermalRasterizer.DitherMode.FLOYD_STEINBERG.name)
        val dither = try {
            ThermalRasterizer.DitherMode.valueOf(ditherStr ?: ThermalRasterizer.DitherMode.FLOYD_STEINBERG.name)
        } catch (e: Exception) {
            ThermalRasterizer.DitherMode.FLOYD_STEINBERG
        }
        val invert = prefs.getBoolean(KEY_INVERT_POLARITY, false)

        return ThermalSettingsState(
            defaultLabelSize = size,
            darkness = darkness,
            speedIps = speed,
            ditherMode = dither,
            invertPolarity = invert
        )
    }

    fun updateLabelSize(size: String) {
        prefs.edit().putString(KEY_LABEL_SIZE, size).apply()
        _settings.value = _settings.value.copy(defaultLabelSize = size)
    }

    fun updateDarkness(darkness: Int) {
        val clamped = darkness.coerceIn(0, 30)
        prefs.edit().putInt(KEY_DARKNESS, clamped).apply()
        _settings.value = _settings.value.copy(darkness = clamped)
    }

    fun updateSpeed(speedIps: Int) {
        val clamped = speedIps.coerceIn(2, 6)
        prefs.edit().putInt(KEY_SPEED, clamped).apply()
        _settings.value = _settings.value.copy(speedIps = clamped)
    }

    fun updateDitherMode(mode: ThermalRasterizer.DitherMode) {
        prefs.edit().putString(KEY_DITHER, mode.name).apply()
        _settings.value = _settings.value.copy(ditherMode = mode)
    }

    fun updateInvertPolarity(invert: Boolean) {
        prefs.edit().putBoolean(KEY_INVERT_POLARITY, invert).apply()
        _settings.value = _settings.value.copy(invertPolarity = invert)
    }
}
