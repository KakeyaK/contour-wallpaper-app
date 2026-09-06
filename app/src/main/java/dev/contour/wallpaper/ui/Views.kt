package dev.contour.wallpaper.ui

import android.app.Activity
import android.view.View

// findViewById só é genérico a partir da API 26; estas versões funcionam em qualquer android.jar.
@Suppress("UNCHECKED_CAST")
fun <T : View> View.byId(id: Int): T = findViewById(id) as T

@Suppress("UNCHECKED_CAST")
fun <T : View> Activity.byId(id: Int): T = findViewById(id) as T
