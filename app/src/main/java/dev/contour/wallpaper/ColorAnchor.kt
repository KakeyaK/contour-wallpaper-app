package dev.contour.wallpaper

import org.json.JSONArray
import org.json.JSONObject

/** Uma âncora da paleta do dia: um instante (0.0–24.0) e as cinco cores daquele momento. */
data class ColorAnchor(
    val hour: Float,
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val line1: Int,
    val line2: Int,
    val line3: Int,
    /**
     * Cor base do modo simples, ou null quando as cinco cores foram escolhidas à mão.
     * As cinco cores continuam sempre gravadas — o seed serve para a tela lembrar de que
     * cor elas vieram, então o desenho e a interpolação não mudam nada.
     */
    val seed: Int? = null,
) {
    /** Recalcula as cinco cores a partir de [seed], mantendo hora e nome. */
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

/** As cinco cores resolvidas para um instante do dia. */
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
