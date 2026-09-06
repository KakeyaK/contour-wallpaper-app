package dev.contour.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader

/**
 * Compositor de três passos: gradiente vertical + PNGs de linhas tingidos com SRC_IN.
 * Mantém UM bitmap por camada e troca só o ColorFilter — nunca cria cópias tingidas.
 *
 * @param sampleSize inSampleSize ao decodificar (1 no wallpaper; 2 na prévia).
 */
class ContourRenderer(context: Context, private val sampleSize: Int = 1) {

    enum class AssetSet(val suffix: String) { COVER("cover"), MAIN("main") }

    private val appContext = context.applicationContext
    private val lock = Any()

    private var loadedSet: AssetSet? = null
    private var loadedMode: LayerMode? = null
    private var bitmaps: List<Bitmap> = emptyList()

    private val gradientPaint = Paint()
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /** Mais largo que ~0.7 → tela interna do dobrável (assets _main); senão _cover. */
    fun chooseSet(width: Int, height: Int): AssetSet =
        if (height > 0 && width.toFloat() / height > 0.7f) AssetSet.MAIN else AssetSet.COVER

    private fun fileNames(set: AssetSet, mode: LayerMode): List<String> = when (mode) {
        LayerMode.SINGLE -> listOf("linhas_${set.suffix}.png")
        LayerMode.THREE -> (1..3).map { "linhas${it}_${set.suffix}.png" }
    }

    /** Carrega (se preciso) o conjunto pedido, liberando o anterior. Pode ser chamado fora da main thread. */
    fun ensureAssets(set: AssetSet, mode: LayerMode) {
        synchronized(lock) {
            if (set == loadedSet && mode == loadedMode && bitmaps.isNotEmpty()) return
            releaseLocked()
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }
            val loaded = ArrayList<Bitmap>(3)
            for (name in fileNames(set, mode)) {
                val bmp = runCatching {
                    appContext.assets.open(name).use { BitmapFactory.decodeStream(it, null, opts) }
                }.getOrNull()
                if (bmp != null) loaded += bmp
            }
            bitmaps = loaded
            loadedSet = set
            loadedMode = mode
        }
    }

    /** Desenha a composição completa em [canvas] para uma superfície de [width]×[height]. */
    fun draw(canvas: Canvas, width: Int, height: Int, colors: DayColors, mode: LayerMode) {
        if (width <= 0 || height <= 0) return

        // 1. Fundo
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            colors.bgTop, colors.bgBottom, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)

        // 2. Linhas
        synchronized(lock) {
            if (bitmaps.isEmpty()) return
            val lineColors: IntArray = when (mode) {
                LayerMode.SINGLE -> intArrayOf(colors.line2)
                LayerMode.THREE -> colors.lines
            }
            for ((i, bmp) in bitmaps.withIndex()) {
                val color = lineColors[i.coerceAtMost(lineColors.lastIndex)]
                layerPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                computeDst(bmp, width, height)
                canvas.drawBitmap(bmp, null, dst, layerPaint)
            }
        }
    }

    /**
     * Encaixa pela altura e centraliza horizontalmente, cortando o excesso (preserva o
     * alinhamento cover/main). Se ainda assim sobrar largura (superfície de parallax
     * muito larga), sobe a escala para não deixar bordas vazias.
     */
    private fun computeDst(bmp: Bitmap, width: Int, height: Int) {
        var scale = height.toFloat() / bmp.height
        if (bmp.width * scale < width) scale = width.toFloat() / bmp.width
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dst.set(left, top, left + w, top + h)
    }

    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps = emptyList()
        loadedSet = null
        loadedMode = null
    }
}
