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
 * Three-pass compositor: vertical gradient + line PNGs tinted with SRC_IN.
 * Keeps ONE bitmap per layer and only swaps the ColorFilter — never creates tinted copies.
 *
 * @param sampleSize inSampleSize when decoding (1 in the wallpaper; 2 in the preview).
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

    /** Wider than ~0.7 → foldable's inner screen (_main assets); otherwise _cover. */
    fun chooseSet(width: Int, height: Int): AssetSet =
        if (height > 0 && width.toFloat() / height > 0.7f) AssetSet.MAIN else AssetSet.COVER

    private fun fileNames(set: AssetSet, mode: LayerMode): List<String> = when (mode) {
        LayerMode.SINGLE -> listOf("lines_${set.suffix}.png")
        LayerMode.THREE -> (1..3).map { "lines${it}_${set.suffix}.png" }
    }

    /** Loads the requested set (if needed), releasing the previous one. May be called off the main thread. */
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

    /** Draws the full composition into [canvas] for a [width]×[height] surface. */
    fun draw(canvas: Canvas, width: Int, height: Int, colors: DayColors, mode: LayerMode) {
        if (width <= 0 || height <= 0) return

        // 1. Background
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            colors.bgTop, colors.bgBottom, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)

        // 2. Lines
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
     * Fits to height and centres horizontally, cropping the excess (keeps the cover/main
     * alignment). If there is still width left over (a very wide parallax surface), the
     * scale goes up so no empty edges are left.
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
