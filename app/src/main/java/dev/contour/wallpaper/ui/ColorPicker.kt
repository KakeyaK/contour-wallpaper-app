package dev.contour.wallpaper.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import dev.contour.wallpaper.ColorMath
import dev.contour.wallpaper.R

/** Paints a View as a colour swatch (rounded corners + thin border). */
fun View.setSwatchColor(color: Int, radiusDp: Float = 8f) {
    val d = GradientDrawable().apply {
        setShape(GradientDrawable.RECTANGLE)
        setColor(color)
        setCornerRadius(radiusDp * resources.displayMetrics.density)
        setStroke(1, 0x33000000)
    }
    background = d
}

/** Colour picker: swatch + typed hex + three SeekBars (HSV). */
fun showColorPicker(context: Context, title: String, initial: Int, onPick: (Int) -> Unit) {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
    val swatch = view.byId<View>(R.id.picker_swatch)
    val hexEdit = view.byId<EditText>(R.id.picker_hex)
    val seekH = view.byId<SeekBar>(R.id.seek_h)
    val seekS = view.byId<SeekBar>(R.id.seek_s)
    val seekV = view.byId<SeekBar>(R.id.seek_v)

    val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    var current = initial
    var updatingFromCode = false

    fun applyColor(c: Int, fromHex: Boolean) {
        current = c
        swatch.setSwatchColor(c)
        updatingFromCode = true
        if (!fromHex) hexEdit.setText(ColorMath.toHex(c))
        seekH.progress = hsv[0].toInt()
        seekS.progress = (hsv[1] * 100).toInt()
        seekV.progress = (hsv[2] * 100).toInt()
        updatingFromCode = false
    }

    val seekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser || updatingFromCode) return
            hsv[0] = seekH.progress.toFloat().coerceIn(0f, 359.99f)
            hsv[1] = seekS.progress / 100f
            hsv[2] = seekV.progress / 100f
            applyColor(Color.HSVToColor(hsv), fromHex = false)
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }
    seekH.setOnSeekBarChangeListener(seekListener)
    seekS.setOnSeekBarChangeListener(seekListener)
    seekV.setOnSeekBarChangeListener(seekListener)

    hexEdit.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (updatingFromCode) return
            val parsed = ColorMath.parseHex(s?.toString() ?: "")
            if (parsed != null) {
                Color.colorToHSV(parsed, hsv)
                applyColor(parsed, fromHex = true)
                hexEdit.error = null
            } else {
                hexEdit.error = "Use #RRGGBB"
            }
        }
    })

    applyColor(initial, fromHex = false)

    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(view)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("OK") { _, _ -> onPick(current) }
        .show()
}

/**
 * A "swatch + label + hex" row (row_color layout) that opens the picker when tapped.
 * Returns a function to update the displayed colour.
 */
fun bindColorRow(row: View, label: String, initial: Int, onChange: (Int) -> Unit): (Int) -> Unit {
    val swatch = row.byId<View>(R.id.color_swatch)
    val labelView = row.byId<TextView>(R.id.color_label)
    val hexView = row.byId<TextView>(R.id.color_hex)
    var current = initial
    labelView.text = label

    fun show(c: Int) {
        current = c
        swatch.setSwatchColor(c)
        hexView.text = ColorMath.toHex(c)
    }
    show(initial)
    row.setOnClickListener {
        showColorPicker(row.context, label, current) { picked ->
            show(picked)
            onChange(picked)
        }
    }
    return ::show
}
