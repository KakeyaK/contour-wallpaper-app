package dev.contour.wallpaper

object Palette {

    const val DEFAULT_CONTRAST = 2.1f
    const val DEFAULT_INTERVAL_MINUTES = 1

    private fun hex(s: String): Int = ColorMath.parseHex(s)!!

    /**
     * As seis âncoras da spec, já com a trava de contraste aplicada.
     *
     * Tarde e Anoitecer tiveram o topo suavizado em relação à tabela original: o
     * #F6D8A8 das 17h e o #432A5C das 20h ficavam berrantes na tela inteira. O topo
     * das 17h passou a um cinza-quente frio (céu real de fim de tarde) e o das 20h a
     * um violeta mais fundo. Com o fundo novo, duas cores de linha caíam abaixo de
     * 2.1 de contraste, então já entram aqui com o valor que a trava produziria:
     * 17h line2 #C56B14 -> #A95C11 e 20h line3 #2B1930 -> #190F1C.
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
     * Cores para o instante [hour] (0..24). Acha as duas âncoras vizinhas (com volta pela
     * meia-noite), aplica smoothstep na fração e mistura cada papel em RGB.
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
            // Trecho que atravessa a meia-noite: última âncora -> primeira do dia seguinte.
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

    /** Trava de contraste sobre as três cores de linha, contra a média do fundo. */
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
