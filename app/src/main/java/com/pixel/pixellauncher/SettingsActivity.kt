package com.pixel.pixellauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val prefs = getSharedPreferences("pixel_launcher_prefs", Context.MODE_PRIVATE)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000")) // Slight dim over wallpaper
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "LAUNCHER // SETTINGS"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f
            setPadding(0, 0, 0, dp(32))
        }
        container.addView(title)

        // 1. Grid Size Setting
        val gridLabel = createLabel("APP DRAWER COLUMNS")
        val gridRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(24))
        }

        val currentGrid = prefs.getInt("grid_columns", 4)
        for (i in 3..5) {
            val btn = createButton("$i", if (i == currentGrid) "#D71921" else "#33FFFFFF") {
                prefs.edit().putInt("grid_columns", i).apply()
                Toast.makeText(this, "Grid set to $i columns. Press Back to apply.", Toast.LENGTH_SHORT).show()
                // Restart activity to refresh button states visually
                finish()
                startActivity(intent)
            }
            btn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i < 5) dp(8) else 0
            }
            gridRow.addView(btn)
        }
        container.addView(gridLabel)
        container.addView(gridRow)

        // 2. Unhide Apps Setting
        val hiddenAppsLabel = createLabel("APP VISIBILITY")
        val unhideBtn = createButton("UNHIDE ALL APPS", "#33FFFFFF") {
            prefs.edit().putStringSet("hidden_apps", setOf()).apply()
            Toast.makeText(this, "All apps are now visible.", Toast.LENGTH_SHORT).show()
        }
        unhideBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(24)
        }
        container.addView(hiddenAppsLabel)
        container.addView(unhideBtn)

        // 3. System Defaults & Info
        val sysLabel = createLabel("SYSTEM")
        val defaultBtn = createButton("SET AS DEFAULT LAUNCHER", "#33FFFFFF") {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        }
        defaultBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }

        val devBtn = createButton("DEVELOPER // GOUTHAM", "#1E1E24") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pixelgoutham.tech")))
        }

        container.addView(sysLabel)
        container.addView(defaultBtn)
        container.addView(devBtn)

        root.addView(container)
        setContentView(root)
    }

    private fun createLabel(textLabel: String): TextView {
        return TextView(this).apply {
            text = textLabel
            setTextColor(Color.parseColor("#A8C7FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            setPadding(dp(4), 0, 0, dp(8))
        }
    }

    private fun createButton(textLabel: String, strokeHex: String, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = textLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9121216"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(5)
                    onClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }
        return tv
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}