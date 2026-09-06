package dev.contour.wallpaper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import dev.contour.wallpaper.ContourRenderer
import dev.contour.wallpaper.DayColors
import dev.contour.wallpaper.LayerMode
import dev.contour.wallpaper.Palette

/**
 * Prévia ao vivo usando o mesmo compositor do serviço. Proporção fixa 1080:2520 (assets
 * _cover), decodificados com inSampleSize = 2 para poupar memória na Activity.
 */
class PreviewView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val renderer = ContourRenderer(context, sampleSize = 2)
    private var colors: DayColors = Palette.resolve(Palette.DEFAULT_ANCHORS, 12f, Palette.DEFAULT_CONTRAST)
    private var mode: LayerMode = LayerMode.THREE
    private var loadedMode: LayerMode? = null

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 18f * resources.displayMetrics.density)
            }
        }
        clipToOutline = true
    }

    fun update(colors: DayColors, mode: LayerMode) {
        this.colors = colors
        if (mode != this.mode || loadedMode != mode) {
            this.mode = mode
            loadAsync()
        }
        invalidate()
    }

    private fun loadAsync() {
        val m = mode
        Thread {
            renderer.ensureAssets(ContourRenderer.AssetSet.COVER, m)
            post {
                loadedMode = m
                invalidate()
            }
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadAsync()
    }

    override fun onDetachedFromWindow() {
        loadedMode = null
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val w = (h * 1080f / 2520f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height, colors, mode)
    }
}
