package dev.contour.wallpaper

object Palette {

    const val DEFAULT_CONTRAST = 2.1f
    const val DEFAULT_INTERVAL_MINUTES = 1

    private fun hex(s: String): Int = ColorMath.parseHex(s)!!

    /**
     * The spec's six anchors, with the contrast lock already applied.
     *
     * Afternoon and Dusk had their top softened compared with the original table: the
     * 17:00 #F6D8A8 and the 20:00 #432A5C looked garish filling the whole screen. The
     * 17:00 top became a cool warm-grey (a real late-afternoon sky) and the 20:00 one a
     * deeper violet. With the new background, two line colours fell below 2.1 contrast,
     * so they ship here with the value the lock would produce:
     * 17:00 line2 #C56B14 -> #A95C11 and 20:00 line3 #2B1930 -> #190F1C.
     */
    val DEFAULT_ANCHORS: List<ColorAnchor> = listOf(
        ColorAnchor(5.0f, "Dawn", hex("#1B2A4A"), hex("#C98B7A"), hex("#FFC9A3"), hex("#FFE8D6"), hex("#AC93AE")),
        ColorAnchor(9.0f, "Morning", hex("#BCD6EE"), hex("#EEF4F8"), hex("#4A7FA8"), hex("#72A3C6"), hex("#2C5876")),
        ColorAnchor(13.0f, "Midday", hex("#F4F1E8"), hex("#DFE6E4"), hex("#6B7F78"), hex("#94A79C"), hex("#3F5450")),
        ColorAnchor(17.0f, "Afternoon", hex("#C2B2AE"), hex("#DE9468"), hex("#A8522F"), hex("#A95C11"), hex("#6D2F1C")),
        ColorAnchor(20.0f, "Dusk", hex("#2E2140"), hex("#A85670"), hex("#FF9E7D"), hex("#FFD0B0"), hex("#190F1C")),
        ColorAnchor(23.0f, "Night", hex("#05070F"), hex("#131B2E"), hex("#5B7FB0"), hex("#93B3D9"), hex("#364D73")),
    )

    /**
     * Colours for the moment [hour] (0..24). Finds the two neighbouring anchors (wrapping
     * around midnight), applies smoothstep to the fraction and blends each role in RGB.
     */
    fun colorsAt(anchors: List<ColorAnchor>, hour: Float): DayColors {
        require(anchors.isNotEmpty())
        val sorted = anchors.sortedBy { it.hour }
        if (sorted.size == 1) return sorted[0].toDayColors()

        val t = ((hour % 24f) + 24f) % 24f
        val idx = sorted.indexOfLast { it.hour <= t }

        val a: ColorAnchor
        val b: ColorAnchor
        val span: Float
        val elapsed: Float
        if (idx == -1 || idx == sorted.lastIndex) {
            // Segment crossing midnight: last anchor -> first anchor of the next day.
            a = sorted.last()
            b = sorted.first()
            span = 24f - a.hour + b.hour
            elapsed = if (t >= a.hour) t - a.hour else t + 24f - a.hour
        } else {
            a = sorted[idx]
            b = sorted[idx + 1]
            span = b.hour - a.hour
            elapsed = t - a.hour
        }

        val raw = if (span <= 1e-4f) 0f else (elapsed / span).coerceIn(0f, 1f)
        val f = ColorMath.smoothstep(raw)

        return DayColors(
            bgTop = ColorMath.lerp(a.bgTop, b.bgTop, f),
            bgBottom = ColorMath.lerp(a.bgBottom, b.bgBottom, f),
            line1 = ColorMath.lerp(a.line1, b.line1, f),
            line2 = ColorMath.lerp(a.line2, b.line2, f),
            line3 = ColorMath.lerp(a.line3, b.line3, f),
        )
    }

    /** Contrast lock on the three line colours, against the background average. */
    fun applyContrast(colors: DayColors, target: Float): DayColors {
        if (target <= 1.0f) return colors
        val bg = ColorMath.average(colors.bgTop, colors.bgBottom)
        return colors.copy(
            line1 = ColorMath.ensureContrast(colors.line1, bg, target),
            line2 = ColorMath.ensureContrast(colors.line2, bg, target),
            line3 = ColorMath.ensureContrast(colors.line3, bg, target),
        )
    }

    fun resolve(anchors: List<ColorAnchor>, hour: Float, contrastTarget: Float): DayColors =
        applyContrast(colorsAt(anchors, hour), contrastTarget)

    private fun ColorAnchor.toDayColors() = DayColors(bgTop, bgBottom, line1, line2, line3)

    fun formatHour(hour: Float): String {
        val total = (hour * 60f).toInt().coerceIn(0, 24 * 60 - 1)
        return String.format("%02d:%02d", total / 60, total % 60)
    }
}
