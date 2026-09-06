package dev.contour.wallpaper.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.contour.wallpaper.ColorAnchor
import dev.contour.wallpaper.Palette
import dev.contour.wallpaper.PaletteRepository
import dev.contour.wallpaper.R
import dev.contour.wallpaper.SolarTime
import dev.contour.wallpaper.SolarWarp
import java.util.Calendar

/**
 * Editor de uma âncora. Extra [EXTRA_INDEX] = índice na lista ordenada, ou -1 para nova.
 * Grava no repositório ao salvar; o serviço e a tela principal reagem sozinhos.
 */
class AnchorEditorActivity : Activity() {

    private lateinit var repo: PaletteRepository
    private var index = -1
    private var anchor: ColorAnchor = Palette.DEFAULT_ANCHORS[0]

    private lateinit var editName: EditText
    private lateinit var textHour: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anchor_editor)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        repo = PaletteRepository(this)
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        val anchors = repo.anchors
        val existing = anchors.getOrNull(index)
        anchor = existing ?: newAnchorTemplate(anchors)
        title = if (existing == null) "New anchor" else "Edit anchor"

        editName = byId(R.id.edit_name)
        textHour = byId(R.id.text_hour)
        editName.setText(anchor.name)
        showHour()

        byId<Button>(R.id.btn_hour).setOnClickListener {
            val total = (anchor.hour * 60f).toInt().coerceIn(0, 24 * 60 - 1)
            TimePickerDialog(
                this,
                { _, h, m -> anchor = anchor.copy(hour = h + m / 60f); showHour() },
                total / 60, total % 60, true,
            ).show()
        }

        val bgRows = byId<LinearLayout>(R.id.bg_rows)
        val lineRows = byId<LinearLayout>(R.id.line_rows)
        addRow(bgRows, "Top", anchor.bgTop) { anchor = anchor.copy(bgTop = it) }
        addRow(bgRows, "Bottom", anchor.bgBottom) { anchor = anchor.copy(bgBottom = it) }
        addRow(lineRows, "Line 1 — lowest band", anchor.line1) { anchor = anchor.copy(line1 = it) }
        addRow(lineRows, "Line 2 — middle band", anchor.line2) { anchor = anchor.copy(line2 = it) }
        addRow(lineRows, "Line 3 — highest band", anchor.line3) { anchor = anchor.copy(line3 = it) }

        byId<Button>(R.id.btn_save).setOnClickListener { save() }

        val btnDelete = byId<Button>(R.id.btn_delete)
        if (existing == null) {
            btnDelete.visibility = android.view.View.GONE
        } else {
            val canDelete = anchors.size > 2
            btnDelete.isEnabled = canDelete
            if (!canDelete) btnDelete.text = "Delete (2 anchors minimum)"
            btnDelete.setOnClickListener { confirmDelete() }
        }
    }

    private fun addRow(container: LinearLayout, label: String, initial: Int, onChange: (Int) -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_color, container, false)
        bindColorRow(row, label, initial, onChange)
        container.addView(row)
    }

    private fun showHour() {
        textHour.text = Palette.formatHour(anchor.hour)
        showSolarNote()
    }

    /** Com o modo solar ligado, este horário não é o que vale: mostrar o real. */
    private fun showSolarNote() {
        val note = byId<TextView>(R.id.solar_note)
        val sun = if (repo.solarEnabled && repo.hasLocation) {
            SolarTime.forDate(Calendar.getInstance(), repo.latitude.toDouble(), repo.longitude.toDouble())
        } else {
            null
        }
        if (sun == null) {
            note.visibility = android.view.View.GONE
            return
        }
        val real = SolarWarp.unwarp(anchor.hour, sun)
        note.visibility = android.view.View.VISIBLE
        note.text = "Solar mode is on, so this time is overridden: today this anchor is reached at " +
            Palette.formatHour(real) + "."
    }

    private fun save() {
        val edited = anchor.copy(name = editName.text.toString().trim())
        val list = repo.anchors.toMutableList()
        if (index in list.indices) list[index] = edited else list += edited
        repo.anchors = list
        finish()
    }

    private fun confirmDelete() {
        val label = anchor.name.ifBlank { Palette.formatHour(anchor.hour) }
        AlertDialog.Builder(this)
            .setTitle("Delete anchor?")
            .setMessage("“$label” will be removed from the palette.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val list = repo.anchors
                if (list.size > 2 && index in list.indices) {
                    repo.anchors = list.filterIndexed { i, _ -> i != index }
                }
                finish()
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_INDEX = "index"

        /** Nova âncora: hora atual e as cores interpoladas desse instante. */
        fun newAnchorTemplate(anchors: List<ColorAnchor>): ColorAnchor {
            val c = Calendar.getInstance()
            val hour = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
            val colors = Palette.colorsAt(anchors, hour)
            return ColorAnchor(hour, "", colors.bgTop, colors.bgBottom, colors.line1, colors.line2, colors.line3)
        }
    }
}
