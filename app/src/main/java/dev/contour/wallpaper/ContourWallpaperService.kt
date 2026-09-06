package dev.contour.wallpaper

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.Calendar

class ContourWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ContourEngine()

    inner class ContourEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val repo = PaletteRepository(this@ContourWallpaperService)
        private val renderer = ContourRenderer(this@ContourWallpaperService)

        private var visible = false
        private var width = 0
        private var height = 0

        private val tick = Runnable {
            drawFrame()
            scheduleNext()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            repo.registerListener(this)
        }

        override fun onDestroy() {
            handler.removeCallbacks(tick)
            repo.unregisterListener(this)
            renderer.release()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            // Não assumir o tamanho da tela: launchers pedem superfícies mais largas.
            width = w
            height = h
            renderer.ensureAssets(renderer.chooseSet(w, h), repo.layerMode)
            if (visible) {
                drawFrame()
                scheduleNext()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                drawFrame()
                scheduleNext()
            } else {
                // Tela apagada / outro app: cancela tudo e para completamente.
                handler.removeCallbacks(tick)
            }
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key == PaletteRepository.KEY_LAYER_MODE) {
                renderer.ensureAssets(renderer.chooseSet(width, height), repo.layerMode)
            }
            if (visible) {
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
        }

        /** Agenda o próximo redesenho na virada do próximo intervalo. Só quando visível. */
        private fun scheduleNext() {
            handler.removeCallbacks(tick)
            if (!visible) return
            val intervalMs = repo.intervalMinutes * 60_000L
            val now = System.currentTimeMillis()
            val delay = intervalMs - (now % intervalMs) + 50L
            handler.postDelayed(tick, delay)
        }

        /**
         * Hora que a paleta usa. Com o modo solar ligado, a hora do relógio passa pelo
         * SolarWarp — só aritmética sobre as coordenadas já guardadas, sem consultar
         * localização nem rede aqui dentro.
         */
        private fun currentHour(): Float {
            val c = Calendar.getInstance()
            val clock = c.get(Calendar.HOUR_OF_DAY) +
                c.get(Calendar.MINUTE) / 60f +
                c.get(Calendar.SECOND) / 3600f
            return SolarWarp.paletteHour(clock, repo.solarEnabled, repo.latitude, repo.longitude, c)
        }

        private fun drawFrame() {
            if (width <= 0 || height <= 0) return
            val holder = surfaceHolder ?: return
            val canvas = try {
                holder.lockCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                val mode = repo.layerMode
                val colors = Palette.resolve(repo.anchors, currentHour(), repo.contrastTarget)
                renderer.draw(canvas, width, height, colors, mode)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }
}
