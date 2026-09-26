package dev.contour.wallpaper

/**
 * Simple mode: builds an anchor's five colours from ONE colour picked by the user.
 *
 * The rule exists because picking the five colours by hand has no criterion at all, and
 * the result tends to land at one of two extremes: either the lines vanish into the
 * background, or they clash with it. Here the lines are born with contrast inside a
 * comfortable range — neither below the legible minimum nor blowing past it — and in the
 * same hue territory as the background.
 *
 * What the rule does:
 *  1. **Background**: two versions of the base colour, a slightly darker one at the top and
 *     a lighter one at the bottom, with a slight hue shift and less saturation at the
 *     bottom — which is what the sky actually does. If the base colour is very close to
 *     black or white, the whole pair slides inward into range, otherwise the gradient
 *     flattens.
 *  2. **Line side**: a dark background calls for lighter lines; any other case calls for
 *     darker lines, which is what makes a topographic map look printed.
 *  3. **Target contrast per altitude band**: 2.3, 3.1 and 4.2. The low band stays subtle
 *     and the high one more pronounced, giving depth without any line shouting. The target
 *     is never below the legibility lock chosen in the settings, so the lock never has to
 *     correct anything afterwards.
 *  4. **Hue and saturation**: the lines stay within a few degrees of the base colour
 *     (-12°, +6°, +18°), with the middle one desaturated — enough variation for the three
 *     bands to separate without leaving the colour family.
 */
object SimplePalette {

    /** Target contrast of each altitude band against the background average. */
    private val TARGETS = floatArrayOf(2.3f, 3.1f, 4.2f)

    /** Hue shift of each line, in turns (1.0 = 360°). */
    private val HUE_SHIFT = floatArrayOf(-12f / 360f, 6f / 360f, 18f / 360f)

    private val SAT_MULTIPLIER = floatArrayOf(0.85f, 0.62f, 1.0f)

    private const val TOP_OFFSET = -0.07f
    private const val BOTTOM_OFFSET = 0.09f
    private const val MIN_LIGHTNESS = 0.05f
    private const val MAX_LIGHTNESS = 0.95f

    /** Below this luminance the background counts as dark and the lines go light. */
    private const val DARK_BACKGROUND = 0.18

    /**
     * Derives the five colours from [seed]. [contrastFloor] is the legibility lock from
     * the settings: the targets never go below it.
     */
    fun derive(seed: Int, contrastFloor: Float = Palette.DEFAULT_CONTRAST): DayColors {
        val hls = ColorMath.rgbToHls(seed)
        val hue = hls[0]
        val lightness = hls[1]
        val saturation = hls[2]

        var lTop = lightness + TOP_OFFSET
        var lBottom = lightness + BOTTOM_OFFSET
        val span = lBottom - lTop
        if (lTop < MIN_LIGHTNESS) {
            lTop = MIN_LIGHTNESS
            lBottom = MIN_LIGHTNESS + span
        }
        if (lBottom > MAX_LIGHTNESS) {
            lBottom = MAX_LIGHTNESS
            lTop = MAX_LIGHTNESS - span
        }

        val bgTop = ColorMath.hlsToRgb(hue, lTop, saturation * 0.95f)
        val bgBottom = ColorMath.hlsToRgb(hue + 8f / 360f, lBottom, saturation * 0.78f)
        val background = ColorMath.average(bgTop, bgBottom)
        val lighterLines = ColorMath.relativeLuminance(background) < DARK_BACKGROUND

        val lines = IntArray(3)
        for (i in 0..2) {
            val target = maxOf(TARGETS[i], contrastFloor)
            val h = hue + HUE_SHIFT[i]
            val s = minOf(1f, saturation * SAT_MULTIPLIER[i])
            var color = colorAtContrast(h, s, background, target, lighterLines)
            // If that side can't reach the target (base too light or too dark),
            // try the other one and keep whichever gets closer.
            if (ColorMath.contrastRatio(color, background) < target - 0.25) {
                val alternative = colorAtContrast(h, s, background, target, !lighterLines)
                val dCurrent = Math.abs(ColorMath.contrastRatio(color, background) - target)
                val dAlt = Math.abs(ColorMath.contrastRatio(alternative, background) - target)
                if (dAlt < dCurrent) color = alternative
            }
            lines[i] = color
        }

        return DayColors(bgTop, bgBottom, lines[0], lines[1], lines[2])
    }

    /**
     * Sweeps lightness and returns the colour whose contrast against [background] gets
     * closest to [target], staying on the requested side (lighter or darker than the
     * background). A simple sweep: it runs once, when the user picks the colour.
     */
    private fun colorAtContrast(
        hue: Float,
        saturation: Float,
        background: Int,
        target: Float,
        lighter: Boolean,
    ): Int {
        val bgLuminance = ColorMath.relativeLuminance(background)
        var best = ColorMath.hlsToRgb(hue, if (lighter) 1f else 0f, saturation)
        var bestDistance = Double.MAX_VALUE
        for (i in 0..100) {
            val candidate = ColorMath.hlsToRgb(hue, i / 100f, saturation)
            val luminance = ColorMath.relativeLuminance(candidate)
            if (lighter && luminance < bgLuminance) continue
            if (!lighter && luminance > bgLuminance) continue
            val distance = Math.abs(ColorMath.contrastRatio(candidate, background) - target)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }
}
