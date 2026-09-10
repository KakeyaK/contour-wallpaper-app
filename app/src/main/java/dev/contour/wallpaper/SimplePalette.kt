package dev.contour.wallpaper

/**
 * Modo simples: monta as cinco cores de uma âncora a partir de UMA cor escolhida
 * pelo usuário.
 *
 * A regra existe porque montar as cinco cores à mão não tem critério nenhum, e o
 * resultado costuma cair num de dois extremos: ou as linhas somem no fundo, ou brigam
 * com ele. Aqui as linhas nascem com contraste dentro de uma faixa confortável — nem
 * abaixo do mínimo legível, nem estourando — e no mesmo território de matiz do fundo.
 *
 * O que a regra faz:
 *  1. **Fundo**: duas versões da cor base, uma um pouco mais escura em cima e outra mais
 *     clara embaixo, com um leve desvio de matiz e menos saturação na de baixo — é o que
 *     o céu faz de verdade. Se a cor base estiver muito perto do preto ou do branco, o
 *     par inteiro é deslocado para dentro da faixa, senão o gradiente achata.
 *  2. **Lado das linhas**: fundo escuro pede linhas mais claras; qualquer outro caso pede
 *     linhas mais escuras, que é o que faz um mapa topográfico parecer impresso.
 *  3. **Contraste alvo por faixa de altitude**: 2.3, 3.1 e 4.2. A faixa baixa fica
 *     discreta e a alta mais marcada, dando profundidade sem nenhuma linha gritar. O
 *     alvo nunca fica abaixo da trava de legibilidade escolhida nas configurações, então
 *     a trava não precisa mexer em nada depois.
 *  4. **Matiz e saturação**: as linhas ficam a poucos graus da cor base (-12°, +6°, +18°),
 *     com a do meio dessaturada — variação suficiente para as três faixas se separarem
 *     sem sair da família de cor.
 */
object SimplePalette {

    /** Contraste alvo de cada faixa de altitude contra a média do fundo. */
    private val TARGETS = floatArrayOf(2.3f, 3.1f, 4.2f)

    /** Desvio de matiz de cada linha, em voltas (1.0 = 360°). */
    private val HUE_SHIFT = floatArrayOf(-12f / 360f, 6f / 360f, 18f / 360f)

    private val SAT_MULTIPLIER = floatArrayOf(0.85f, 0.62f, 1.0f)

    private const val TOP_OFFSET = -0.07f
    private const val BOTTOM_OFFSET = 0.09f
    private const val MIN_LIGHTNESS = 0.05f
    private const val MAX_LIGHTNESS = 0.95f

    /** Abaixo desta luminância o fundo é considerado escuro e as linhas vão para o claro. */
    private const val DARK_BACKGROUND = 0.18

    /**
     * Deriva as cinco cores a partir de [seed]. [contrastFloor] é a trava de
     * legibilidade das configurações: os alvos nunca ficam abaixo dela.
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
            // Se aquele lado não alcança o alvo (base muito clara ou muito escura),
            // tenta o outro e fica com o que chega mais perto.
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
     * Varre a luminosidade e devolve a cor cujo contraste contra [background] chega mais
     * perto de [target], mantendo-se do lado pedido (mais clara ou mais escura que o
     * fundo). Varredura simples: roda uma vez, quando o usuário escolhe a cor.
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
