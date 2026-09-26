package dev.contour.wallpaper

import org.json.JSONArray
import org.json.JSONObject

/** An anchor of the day palette: a moment (0.0–24.0) and the five colours for that moment. */
data class ColorAnchor(
    val hour: Float,
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val line1: Int,
    val line2: Int,
    val line3: Int,
    /**
     * Base colour for simple mode, or null when the five colours were picked by hand.
     * The five colours are always stored as well — the seed only lets the screen remember
     * which colour they came from, so drawing and interpolation are unaffected.
     */
    val seed: Int? = null,
) {
    /** Recomputes the five colours from [seed], keeping the hour and name. */
    fun withSeed(seed: Int, contrastFloor: Float): ColorAnchor {
        val c = SimplePalette.derive(seed, contrastFloor)
        return copy(
            bgTop = c.bgTop,
            bgBottom = c.bgBottom,
            line1 = c.line1,
            line2 = c.line2,
            line3 = c.line3,
            seed = seed,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("hour", hour.toDouble())
        put("name", name)
        put("bgTop", ColorMath.toHex(bgTop))
        put("bgBottom", ColorMath.toHex(bgBottom))
        put("line1", ColorMath.toHex(line1))
        put("line2", ColorMath.toHex(line2))
        put("line3", ColorMath.toHex(line3))
        seed?.let { put("seed", ColorMath.toHex(it)) }
    }

    companion object {
        fun fromJson(o: JSONObject): ColorAnchor? {
            val hour = o.optDouble("hour", Double.NaN)
            if (hour.isNaN() || hour < 0.0 || hour > 24.0) return null
            fun c(key: String): Int? = ColorMath.parseHex(o.optString(key, ""))
            return ColorAnchor(
                hour = hour.toFloat(),
                name = o.optString("name", ""),
                bgTop = c("bgTop") ?: return null,
                bgBottom = c("bgBottom") ?: return null,
                line1 = c("line1") ?: return null,
                line2 = c("line2") ?: return null,
                line3 = c("line3") ?: return null,
                seed = if (o.has("seed")) c("seed") else null,
            )
        }

        fun listToJson(list: List<ColorAnchor>): JSONArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray): List<ColorAnchor>? {
            val out = ArrayList<ColorAnchor>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: return null
                out += fromJson(o) ?: return null
            }
            return if (out.size >= 2) out.sortedBy { it.hour } else null
        }
    }
}

/** The five colours resolved for a moment of the day. */
data class DayColors(
    val bgTop: Int,
    val bgBottom: Int,
    val line1: Int,
    val line2: Int,
    val line3: Int,
) {
    val lines: IntArray get() = intArrayOf(line1, line2, line3)
}

enum class LayerMode(val key: String) {
    THREE("three"),
    SINGLE("single");

    companion object {
        fun fromKey(k: String?): LayerMode = entries.firstOrNull { it.key == k } ?: THREE
    }
}
