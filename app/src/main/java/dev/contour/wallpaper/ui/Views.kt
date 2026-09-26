package dev.contour.wallpaper.ui

import android.app.Activity
import android.view.View

// findViewById is only generic from API 26 on; these versions work with any android.jar.
@Suppress("UNCHECKED_CAST")
fun <T : View> View.byId(id: Int): T = findViewById(id) as T

@Suppress("UNCHECKED_CAST")
fun <T : View> Activity.byId(id: Int): T = findViewById(id) as T
