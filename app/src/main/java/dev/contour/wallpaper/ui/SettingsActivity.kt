package dev.contour.wallpaper.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.contour.wallpaper.ColorAnchor
import dev.contour.wallpaper.ContourWallpaperService
import dev.contour.wallpaper.LayerMode
import dev.contour.wallpaper.Palette
import dev.contour.wallpaper.PaletteRepository
import dev.contour.wallpaper.R
import dev.contour.wallpaper.SolarTime
import dev.contour.wallpaper.SolarWarp
import java.util.Calendar
import kotlin.math.roundToInt

class SettingsActivity : Activity() {

    private lateinit var repo: PaletteRepository
    private lateinit var preview: PreviewView
    private lateinit var previewTime: TextView
    private lateinit var seekTime: SeekBar
    private lateinit var anchorList: LinearLayout
    private lateinit var groupLayers: RadioGroup
    private lateinit var seekContrast: SeekBar
    private lateinit var contrastValue: TextView
    private lateinit var groupInterval: RadioGroup
    private lateinit var checkSolar: CheckBox
    private lateinit var solarStatus: TextView
    private lateinit var editLat: EditText
    private lateinit var editLon: EditText

    private var anchors: List<ColorAnchor> = Palette.DEFAULT_ANCHORS
    private var layerMode = LayerMode.THREE
    private var contrast = Palette.DEFAULT_CONTRAST

    /** -1 = real time; otherwise the minute of the day forced by the slider. Resets to real time on leaving the screen. */
    private var forcedMinute = -1
    private var syncing = false

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            if (forcedMinute < 0) refreshPreview()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        repo = PaletteRepository(this)

        preview = byId(R.id.preview)
        previewTime = byId(R.id.preview_time)
        seekTime = byId(R.id.seek_time)
        anchorList = byId(R.id.anchor_list)
        groupLayers = byId(R.id.group_layers)
        seekContrast = byId(R.id.seek_contrast)
        contrastValue = byId(R.id.contrast_value)
        groupInterval = byId(R.id.group_interval)
        checkSolar = byId(R.id.check_solar)
        solarStatus = byId(R.id.solar_status)
        editLat = byId(R.id.edit_lat)
        editLon = byId(R.id.edit_lon)

        seekTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                forcedMinute = progress
                refreshPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        byId<Button>(R.id.btn_now).setOnClickListener {
            forcedMinute = -1
            refreshPreview()
        }
        byId<Button>(R.id.btn_apply).setOnClickListener { openLiveWallpaperChooser() }
        byId<Button>(R.id.btn_add).setOnClickListener { openEditor(-1) }
        byId<Button>(R.id.btn_restore).setOnClickListener { confirmRestore() }

        groupLayers.setOnCheckedChangeListener { _, id ->
            if (syncing) return@setOnCheckedChangeListener
            layerMode = if (id == R.id.radio_single) LayerMode.SINGLE else LayerMode.THREE
            repo.layerMode = layerMode
            refreshPreview()
        }
        seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                contrast = 1f + progress / 10f
                contrastValue.text = if (contrast <= 1.0f) "off" else String.format("%.1f", contrast)
                if (fromUser) {
                    repo.contrastTarget = contrast
                    refreshPreview()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        groupInterval.setOnCheckedChangeListener { _, id ->
            if (syncing) return@setOnCheckedChangeListener
            repo.intervalMinutes = when (id) {
                R.id.radio_5 -> 5
                R.id.radio_15 -> 15
                else -> 1
            }
        }
        checkSolar.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            repo.solarEnabled = checked
            if (checked && !repo.hasLocation) {
                Toast.makeText(this, "Set a location for solar mode to take effect.", Toast.LENGTH_LONG).show()
            }
            refreshSolar()
            rebuildAnchorList()
            refreshPreview()
        }
        val coordWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val lat = editLat.text.toString().trim().toFloatOrNull()
                val lon = editLon.text.toString().trim().toFloatOrNull()
                if (lat == null || lon == null || lat < -90f || lat > 90f || lon < -180f || lon > 180f) return
                repo.setLocation(lat, lon)
                refreshSolar()
                rebuildAnchorList()
                refreshPreview()
            }
        }
        editLat.addTextChangedListener(coordWatcher)
        editLon.addTextChangedListener(coordWatcher)
        byId<Button>(R.id.btn_locate).setOnClickListener { requestLocation() }

        byId<Button>(R.id.btn_export).setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "contour-palette.json")
            }
            startActivityForResult(i, REQ_EXPORT)
        }
        byId<Button>(R.id.btn_import).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
            }
            startActivityForResult(i, REQ_IMPORT)
        }
    }

    override fun onResume() {
        super.onResume()
        reloadAll()
        handler.post(clockTick)
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        super.onPause()
    }

    private fun reloadAll() {
        syncing = true
        anchors = repo.anchors
        layerMode = repo.layerMode
        contrast = repo.contrastTarget
        groupLayers.check(if (layerMode == LayerMode.SINGLE) R.id.radio_single else R.id.radio_three)
        seekContrast.progress = ((contrast - 1f) * 10f).roundToInt().coerceIn(0, 20)
        contrastValue.text = if (contrast <= 1.0f) "off" else String.format("%.1f", contrast)
        groupInterval.check(
            when (repo.intervalMinutes) {
                5 -> R.id.radio_5
                15 -> R.id.radio_15
                else -> R.id.radio_1
            },
        )
        checkSolar.isChecked = repo.solarEnabled
        if (repo.hasLocation) {
            if (editLat.text.toString().trim().toFloatOrNull() != repo.latitude) {
                editLat.setText(String.format("%.4f", repo.latitude))
            }
            if (editLon.text.toString().trim().toFloatOrNull() != repo.longitude) {
                editLon.setText(String.format("%.4f", repo.longitude))
            }
        }
        syncing = false
        refreshSolar()
        rebuildAnchorList()
        refreshPreview()
    }

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** The slider shows the clock time; the palette uses the time already adjusted to the sun. */
    private fun paletteHour(clockHour: Float): Float =
        SolarWarp.paletteHour(clockHour, repo.solarEnabled, repo.latitude, repo.longitude)

    private fun refreshPreview() {
        val minute = if (forcedMinute >= 0) forcedMinute else nowMinutes()
        seekTime.progress = minute
        val clock = minute / 60f
        val palette = paletteHour(clock)
        previewTime.text = if (kotlin.math.abs(palette - clock) < 0.017f) {
            Palette.formatHour(clock)
        } else {
            Palette.formatHour(clock) + "  →  " + Palette.formatHour(palette)
        }
        byId<Button>(R.id.btn_now).text = if (forcedMinute < 0) "Now" else "Back to now"
        preview.update(Palette.resolve(anchors, palette, contrast), layerMode)
    }

    private fun refreshSolar() {
        val enabled = repo.solarEnabled
        solarStatus.text = when {
            !repo.hasLocation -> "No location set."
            else -> {
                val sun = SolarTime.forDate(Calendar.getInstance(), repo.latitude.toDouble(), repo.longitude.toDouble())
                when {
                    sun == null -> "The sun neither rises nor sets here today — the normal clock is used."
                    enabled -> "Overriding anchor times · today sunrise ${Palette.formatHour(sun.sunrise)}, sunset ${Palette.formatHour(sun.sunset)}"
                    else -> "At this location today: sunrise ${Palette.formatHour(sun.sunrise)}, sunset ${Palette.formatHour(sun.sunset)}"
                }
            }
        }
    }

    /** Reads the location ONCE and stores the coordinates. None of this runs in the wallpaper. */
    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Toast.makeText(this, "Location unavailable. Type the coordinates instead.", Toast.LENGTH_LONG).show()
            return
        }
        val best = runCatching {
            lm.getProviders(true)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
        if (best == null) {
            Toast.makeText(
                this,
                "No recent position on this device. Open a maps app once, or type the coordinates.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        syncing = true
        repo.setLocation(best.latitude.toFloat(), best.longitude.toFloat())
        editLat.setText(String.format("%.4f", best.latitude))
        editLon.setText(String.format("%.4f", best.longitude))
        syncing = false
        refreshSolar()
        rebuildAnchorList()
        refreshPreview()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        } else {
            Toast.makeText(this, "Permission denied: type latitude and longitude instead.", Toast.LENGTH_LONG).show()
        }
    }

    private fun rebuildAnchorList() {
        anchorList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val swatchW = (22 * resources.displayMetrics.density).toInt()
        // With solar mode on, the set time isn't the one in effect: show both.
        val sun = if (repo.solarEnabled && repo.hasLocation) {
            SolarTime.forDate(Calendar.getInstance(), repo.latitude.toDouble(), repo.longitude.toDouble())
        } else {
            null
        }
        anchors.forEachIndexed { index, a ->
            val item = inflater.inflate(R.layout.item_anchor, anchorList, false)
            item.byId<TextView>(R.id.anchor_name).text = a.name.ifBlank { "Unnamed" }
            item.byId<TextView>(R.id.anchor_hour).text = if (sun == null) {
                Palette.formatHour(a.hour)
            } else {
                Palette.formatHour(a.hour) + "  →  " + Palette.formatHour(SolarWarp.unwarp(a.hour, sun)) + " today"
            }
            val swatches = item.byId<LinearLayout>(R.id.anchor_swatches)
            listOf(a.bgTop, a.bgBottom, a.line1, a.line2, a.line3).forEach { c ->
                val v = View(this)
                v.setSwatchColor(c, radiusDp = 4f)
                swatches.addView(v, LinearLayout.LayoutParams(swatchW, LinearLayout.LayoutParams.MATCH_PARENT))
            }
            item.setOnClickListener { openEditor(index) }
            anchorList.addView(item)
        }
    }

    private fun openEditor(index: Int) {
        startActivity(Intent(this, AnchorEditorActivity::class.java).putExtra(AnchorEditorActivity.EXTRA_INDEX, index))
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle("Restore defaults?")
            .setMessage("The six original anchors and the drawing options will be restored. Your current palette will be lost.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                repo.restoreDefaults()
                reloadAll()
            }
            .show()
    }

    private fun openLiveWallpaperChooser() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, ContourWallpaperService::class.java),
        )
        val fallback = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        runCatching { startActivity(direct) }
            .recoverCatching { startActivity(fallback) }
            .onFailure { Toast.makeText(this, "Open the system wallpaper picker.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(repo.exportJson().toByteArray()) }
                }.isSuccess
                Toast.makeText(this, if (ok) "Palette exported" else "Export failed", Toast.LENGTH_SHORT).show()
            }
            REQ_IMPORT -> {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val ok = text != null && repo.importJson(text)
                if (ok) reloadAll()
                Toast.makeText(this, if (ok) "Palette imported" else "Invalid file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2
        private const val REQ_LOCATION = 3
    }
}
