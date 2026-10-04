package com.cuppa.app.data.thermal

import android.content.Context
import android.content.SharedPreferences
import com.cuppa.cups.thermal.ThermalRasterizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The printer language a thermal printer speaks. AUTO keeps the make/model keyword detection used
 * by [com.cuppa.app.server.PrintJobDispatcher] so a per-printer override only has to name a dialect
 * when the automatic guess is wrong.
 */
enum class ThermalDialect { AUTO, TSPL, ZPL, EPL, ESCPOS, PCL }

/**
 * Per-printer thermal overrides. Any printer without a stored config keeps using the global
 * [ThermalPreferences] and the automatic dialect detection — these values only apply once the user
 * saves them from the printer's "Thermal settings" dialog.
 *
 * [density] deliberately stays on the global 0..30 darkness scale (rather than TSPL's 0..15) so the
 * dispatcher can keep applying the same per-dialect scaling it already does.
 */
data class PrinterThermalConfig(
    val dialect: ThermalDialect = ThermalDialect.AUTO,
    val labelWidthMm: Double = 101.6,
    val labelHeightMm: Double = 152.4,
    val density: Int = 15,
    val speed: Int = 4,
    val gapMm: Double = 3.0,
    val ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG,
    val invertPolarity: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dialect", dialect.name)
        put("labelWidthMm", labelWidthMm)
        put("labelHeightMm", labelHeightMm)
        put("density", density)
        put("speed", speed)
        put("gapMm", gapMm)
        put("ditherMode", ditherMode.name)
        put("invertPolarity", invertPolarity)
    }

    companion object {
        fun fromJson(json: JSONObject): PrinterThermalConfig {
            val dialect = runCatching {
                ThermalDialect.valueOf(json.optString("dialect", ThermalDialect.AUTO.name))
            }.getOrDefault(ThermalDialect.AUTO)
            val dither = runCatching {
                ThermalRasterizer.DitherMode.valueOf(
                    json.optString("ditherMode", ThermalRasterizer.DitherMode.FLOYD_STEINBERG.name)
                )
            }.getOrDefault(ThermalRasterizer.DitherMode.FLOYD_STEINBERG)
            return PrinterThermalConfig(
                dialect = dialect,
                labelWidthMm = json.optDouble("labelWidthMm", 101.6),
                labelHeightMm = json.optDouble("labelHeightMm", 152.4),
                density = json.optInt("density", 15).coerceIn(0, 30),
                speed = json.optInt("speed", 4).coerceIn(2, 6),
                gapMm = json.optDouble("gapMm", 3.0),
                ditherMode = dither,
                invertPolarity = json.optBoolean("invertPolarity", false)
            )
        }
    }
}

/**
 * ThermalConfigStore — persistent per-printer thermal overrides, keyed by printer URI.
 *
 * A singleton so the UI and the print dispatcher observe the same in-memory map; a fresh instance
 * per call would each cache its own StateFlow and never see the other's writes.
 */
class ThermalConfigStore private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "cuppa_printer_thermal"

        @Volatile
        private var instance: ThermalConfigStore? = null

        fun getInstance(context: Context): ThermalConfigStore {
            return instance ?: synchronized(this) {
                instance ?: ThermalConfigStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configs = MutableStateFlow(loadAll())
    val configs: StateFlow<Map<String, PrinterThermalConfig>> = _configs.asStateFlow()

    /** Returns the saved override for [uri], or null when the printer uses the global defaults. */
    fun get(uri: String): PrinterThermalConfig? {
        val raw = prefs.getString(uri, null) ?: return null
        return runCatching { PrinterThermalConfig.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun set(uri: String, config: PrinterThermalConfig) {
        prefs.edit().putString(uri, config.toJson().toString()).apply()
        _configs.value = _configs.value + (uri to config)
    }

    /** Drops the override so [uri] falls back to the global thermal settings. */
    fun remove(uri: String) {
        prefs.edit().remove(uri).apply()
        _configs.value = _configs.value - uri
    }

    private fun loadAll(): Map<String, PrinterThermalConfig> {
        return prefs.all.mapNotNull { (uri, value) ->
            val raw = value as? String ?: return@mapNotNull null
            runCatching { uri to PrinterThermalConfig.fromJson(JSONObject(raw)) }.getOrNull()
        }.toMap()
    }
}
