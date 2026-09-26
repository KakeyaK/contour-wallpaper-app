package dev.contour.wallpaper

import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

object ColorMath {

    fun toHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

    /** Accepts "#RRGGBB" or "RRGGBB" (upper or lower case). Returns an opaque colour. */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#")
        if (t.length != 6) return null
        val v = t.toLongOrNull(16) ?: return null
        return (0xFF000000L or v).toInt()
    }

    fun smoothstep(f: Float): Float {
        val x = f.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    fun lerp(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b)),
        )
    }

    fun average(a: Int, b: Int): Int = lerp(a, b, 0.5f)

    // ---- WCAG ----

    private fun linearize(c8: Int): Double {
        val c = c8 / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun relativeLuminance(color: Int): Double =
        0.2126 * linearize(Color.red(color)) +
            0.7152 * linearize(Color.green(color)) +
            0.0722 * linearize(Color.blue(color))

    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    // ---- HLS (same convention as Python's colorsys: h, l, s in 0..1) ----

    fun rgbToHls(color: Int): FloatArray {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val maxc = maxOf(r, g, b)
        val minc = minOf(r, g, b)
        val l = (minc + maxc) / 2f
        if (minc == maxc) return floatArrayOf(0f, l, 0f)
        val d = maxc - minc
        val s = if (l <= 0.5f) d / (maxc + minc) else d / (2f - maxc - minc)
        val rc = (maxc - r) / d
        val gc = (maxc - g) / d
        val bc = (maxc - b) / d
        var h = when {
            r == maxc -> bc - gc
            g == maxc -> 2f + rc - bc
            else -> 4f + gc - rc
        }
        h = ((h / 6f) % 1f + 1f) % 1f
        return floatArrayOf(h, l, s)
    }

    private fun hlsValue(m1: Float, m2: Float, hueIn: Float): Float {
        val hue = ((hueIn % 1f) + 1f) % 1f
        return when {
            hue < 1f / 6f -> m1 + (m2 - m1) * hue * 6f
            hue < 0.5f -> m2
            hue < 2f / 3f -> m1 + (m2 - m1) * (2f / 3f - hue) * 6f
            else -> m1
        }
    }

    fun hlsToRgb(h: Float, l: Float, s: Float): Int {
        val lc = l.coerceIn(0f, 1f)
        val sc = s.coerceIn(0f, 1f)
        if (sc == 0f) {
            val v = (lc * 255f).roundToInt()
            return Color.rgb(v, v, v)
        }
        val m2 = if (lc <= 0.5f) lc * (1f + sc) else lc + sc - lc * sc
        val m1 = 2f * lc - m2
        fun to255(x: Float) = (x.coerceIn(0f, 1f) * 255f).roundToInt()
        return Color.rgb(
            to255(hlsValue(m1, m2, h + 1f / 3f)),
            to255(hlsValue(m1, m2, h)),
            to255(hlsValue(m1, m2, h - 1f / 3f)),
        )
    }

    // ---- Contrast lock ----

    /**
     * Ensures [line] has contrast >= [target] against [background].
     * Only lightness (HLS) is changed, in steps of 0.02, trying both directions and
     * keeping the one that reaches the target with the smallest change. If neither
     * direction reaches the target (extreme colours), returns the highest-contrast
     * candidate found. target <= 1.0 disables the lock.
     */
    fun ensureContrast(line: Int, background: Int, target: Float): Int {
        if (target <= 1.0f) return line
        val current = contrastRatio(line, background)
        if (current >= target) return line

        val hls = rgbToHls(line)
        val h = hls[0]
        val l = hls[1]
        val s = hls[2]

        var best = line
        var bestRatio = current
        val step = 0.02f
        var i = 1
        while (true) {
            val up = l + step * i
            val down = l - step * i
            if (up > 1f && down < 0f) break

            var found: Int? = null
            var foundRatio = 0.0
            for (cand in listOf(up, down)) {
                if (cand < 0f || cand > 1f) continue
                val c = hlsToRgb(h, cand, s)
                val r = contrastRatio(c, background)
                if (r > bestRatio) {
                    best = c
                    bestRatio = r
                }
                if (r >= target && r > foundRatio) {
                    found = c
                    foundRatio = r
                }
            }
            if (found != null) return found
            i++
        }
        return best
    }
}
