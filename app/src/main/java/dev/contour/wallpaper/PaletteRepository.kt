package dev.contour.wallpaper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists anchors and preferences in SharedPreferences (JSON).
 * The service listens for changes and redraws immediately.
 */
class PaletteRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var anchors: List<ColorAnchor>
        get() {
            val raw = prefs.getString(KEY_ANCHORS, null) ?: return Palette.DEFAULT_ANCHORS
            return runCatching { ColorAnchor.listFromJson(JSONArray(raw)) }.getOrNull()
                ?: Palette.DEFAULT_ANCHORS
        }
        set(value) {
            val sorted = value.sortedBy { it.hour }
            prefs.edit().putString(KEY_ANCHORS, ColorAnchor.listToJson(sorted).toString()).apply()
        }

    var layerMode: LayerMode
        get() = LayerMode.fromKey(prefs.getString(KEY_LAYER_MODE, null))
        set(value) = prefs.edit().putString(KEY_LAYER_MODE, value.key).apply()

    var contrastTarget: Float
        get() = prefs.getFloat(KEY_CONTRAST, Palette.DEFAULT_CONTRAST).coerceIn(1f, 3f)
        set(value) = prefs.edit().putFloat(KEY_CONTRAST, value.coerceIn(1f, 3f)).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, Palette.DEFAULT_INTERVAL_MINUTES).let {
            if (it in ALLOWED_INTERVALS) it else Palette.DEFAULT_INTERVAL_MINUTES
        }
        set(value) = prefs.edit()
            .putInt(KEY_INTERVAL, if (value in ALLOWED_INTERVALS) value else Palette.DEFAULT_INTERVAL_MINUTES)
            .apply()

    /** Solar mode: the 5:00 and 20:00 anchors follow the actual sunrise and sunset. */
    var solarEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOLAR, false)
        set(value) = prefs.edit().putBoolean(KEY_SOLAR, value).apply()

    /** Coordinates stored only once; the service never queries location. */
    var latitude: Float
        get() = prefs.getFloat(KEY_LAT, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_LAT, value).apply()

    var longitude: Float
        get() = prefs.getFloat(KEY_LON, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_LON, value).apply()

    val hasLocation: Boolean
        get() = !latitude.isNaN() && !longitude.isNaN()

    fun setLocation(lat: Float, lon: Float) {
        prefs.edit().putFloat(KEY_LAT, lat).putFloat(KEY_LON, lon).apply()
    }

    /** Leaves solar mode and coordinates alone: they are location settings, not palette settings. */
    fun restoreDefaults() {
        prefs.edit()
            .putString(KEY_ANCHORS, ColorAnchor.listToJson(Palette.DEFAULT_ANCHORS).toString())
            .putString(KEY_LAYER_MODE, LayerMode.THREE.key)
            .putFloat(KEY_CONTRAST, Palette.DEFAULT_CONTRAST)
            .putInt(KEY_INTERVAL, Palette.DEFAULT_INTERVAL_MINUTES)
            .apply()
    }

    fun exportJson(): String = JSONObject().apply {
        put("version", 1)
        put("anchors", ColorAnchor.listToJson(anchors))
        put("layerMode", layerMode.key)
        put("contrastTarget", contrastTarget.toDouble())
        put("intervalMinutes", intervalMinutes)
    }.toString(2)

    /** Accepts the exported object or just the list of anchors. Returns false if invalid. */
    fun importJson(text: String): Boolean {
        val trimmed = text.trim()
        return runCatching {
            if (trimmed.startsWith("[")) {
                val list = ColorAnchor.listFromJson(JSONArray(trimmed)) ?: return false
                anchors = list
            } else {
                val o = JSONObject(trimmed)
                val list = ColorAnchor.listFromJson(o.getJSONArray("anchors")) ?: return false
                val editor = prefs.edit()
                    .putString(KEY_ANCHORS, ColorAnchor.listToJson(list).toString())
                if (o.has("layerMode")) editor.putString(KEY_LAYER_MODE, LayerMode.fromKey(o.optString("layerMode")).key)
                if (o.has("contrastTarget")) editor.putFloat(KEY_CONTRAST, o.optDouble("contrastTarget", 2.1).toFloat().coerceIn(1f, 3f))
                if (o.has("intervalMinutes")) {
                    val v = o.optInt("intervalMinutes", 1)
                    editor.putInt(KEY_INTERVAL, if (v in ALLOWED_INTERVALS) v else 1)
                }
                editor.apply()
            }
            true
        }.getOrDefault(false)
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val PREFS_NAME = "contour_wallpaper"
        const val KEY_ANCHORS = "anchors_json"
        const val KEY_LAYER_MODE = "layer_mode"
        const val KEY_CONTRAST = "contrast_target"
        const val KEY_INTERVAL = "interval_minutes"
        const val KEY_SOLAR = "solar_enabled"
        const val KEY_LAT = "latitude"
        const val KEY_LON = "longitude"
        val ALLOWED_INTERVALS = listOf(1, 5, 15)
    }
}
