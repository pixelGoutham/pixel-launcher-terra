package com.pixel.pixellauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

data class AppEntry(val label: String, val pkg: String, val icon: Drawable)

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var homeLayer: LinearLayout
    private lateinit var scrimView: View
    private lateinit var drawerSheet: LinearLayout
    private lateinit var appGrid: GridView
    private lateinit var searchInput: EditText
    private lateinit var calcResultPill: TextView
    private lateinit var clockText: TextView
    private lateinit var subHeaderText: TextView
    private lateinit var otpPill: TextView
    private lateinit var widgetSlotContainer: FrameLayout
    private lateinit var contextMenuLayer: FrameLayout

    private val dockIconViews = mutableMapOf<String, ImageView>()
    private val dockBadgeViews = mutableMapOf<String, View>()

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost

    private val drawerSpring = FluidSpring()
    private var velocityTracker: VelocityTracker? = null
    private var screenHeight = 1920f
    private var screenWidth = 1080f
    private var downY = 0f
    private var grabOffsetY = 0f
    private var isDraggingDrawer = false
    private var touchSlop = 24

    private val allApps = mutableListOf<AppEntry>()
    private val filteredApps = mutableListOf<AppEntry>()
    private lateinit var gridAdapter: AppGridAdapter

    companion object {
        private const val HOST_ID = 1024
        private const val REQ_PICK_WIDGET = 101
        private const val REQ_CREATE_WIDGET = 102
        private const val PREFS_NAME = "pixel_launcher_prefs"
        private const val KEY_WIDGET_ID = "bound_widget_id"
    }

    private val minuteTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateHeaderTelemetry()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        screenHeight = resources.displayMetrics.heightPixels.toFloat()
        screenWidth = resources.displayMetrics.widthPixels.toFloat()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        //Meow
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, HOST_ID)

        drawerSpring.grab(screenHeight)
        buildUi()
        MediaTurntableService.onNotificationUpdate = {
            runOnUiThread { updateHeaderTelemetry() }
        }
        restoreBoundWidget()
        loadInstalledAppsAsync()
    }

    override fun onStart() {
        super.onStart()
        appWidgetHost.startListening()
        registerReceiver(minuteTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
    }

    override fun onStop() {
        super.onStop()
        appWidgetHost.stopListening()
        try {
            unregisterReceiver(minuteTickReceiver)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        updateHeaderTelemetry()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_HOME) == true) {
            if (contextMenuLayer.childCount > 0) contextMenuLayer.removeAllViews()
            if (drawerSpring.pos < screenHeight * 0.5f) {
                hideKeyboard()
                drawerSpring.animateTo(screenHeight, 0f) { y -> applyDrawerFrame(y) }
            } else {
                homeLayer.scaleX = 0.93f
                homeLayer.scaleY = 0.93f
                val settleSpring = FluidSpring(0.93f)
                settleSpring.animateTo(1.0f, 2.5f, 0.65f, 0.35f) { scale ->
                    if (drawerSpring.pos >= screenHeight * 0.95f) {
                        homeLayer.scaleX = scale
                        homeLayer.scaleY = scale
                    }
                }
            }
        }
    }

    private fun buildUi() {
        rootLayout = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = handleGestureIntercept(ev)
            override fun onTouchEvent(ev: MotionEvent): Boolean = handleGestureTouch(ev)
        }

        homeLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(22), dp(48), dp(22), dp(20))
            setOnLongClickListener {
                vibrateTick(14)
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                true
            }
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.TOP or Gravity.START
        }

        clockText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 84f)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            letterSpacing = -0.05f
            setShadowLayer(24f, 0f, 8f, Color.parseColor("#99000000"))
        }

        subHeaderText = TextView(this).apply {
            setTextColor(Color.parseColor("#A8C7FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setPadding(dp(4), dp(8), 0, 0)
        }
        attachPressFeedback(subHeaderText) {
            if (EdgeGestureService.instance == null) {
                Toast.makeText(this, "Enable 'Pixel Edge Gestures' for Back/Recents swipe", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        otpPill = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            background = makeGlassCard("#E6D71921", "#55FFFFFF", dp(99))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
                marginStart = dp(4)
            }
        }
        attachPressFeedback(otpPill) {
            val code = MediaTurntableService.latestOtp ?: return@attachPressFeedback
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("OTP", code))
            Toast.makeText(this, "OTP $code copied", Toast.LENGTH_SHORT).show()
            MediaTurntableService.latestOtp = null
            otpPill.visibility = View.GONE
        }

        headerBox.addView(clockText)
        headerBox.addView(subHeaderText)
        headerBox.addView(otpPill)
        homeLayer.addView(headerBox)

        val turntableView = RotaryTurntableView(
            context = this,
            onLaunchPackage = { queryOrPkg ->
                if (queryOrPkg == "spigen") {
                    val eqApp = allApps.firstOrNull { it.label.contains("Spigen", ignoreCase = true) }
                    if (eqApp != null) launchPackage(eqApp.pkg)
                } else {
                    launchPackage(queryOrPkg)
                }
            },
            onRequestPermission = {
                Toast.makeText(this, "Enable Pixel Launcher in Notification Access", Toast.LENGTH_LONG).show()
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        homeLayer.addView(turntableView)

        widgetSlotContainer = FrameLayout(this).apply {
            background = makeGlassCard("#B3121216", "#28FFFFFF", dp(24))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
            minimumHeight = dp(110)
        }
        homeLayer.addView(widgetSlotContainer)
        homeLayer.addView(buildIconDock())

        scrimView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            setOnClickListener {
                if (contextMenuLayer.childCount > 0) {
                    contextMenuLayer.removeAllViews()
                } else if (drawerSpring.pos < screenHeight * 0.5f) {
                    hideKeyboard()
                    drawerSpring.animateTo(screenHeight, 0f) { y -> applyDrawerFrame(y) }
                }
            }
        }

        drawerSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeGlassCard("#F20C0C10", "#38FFFFFF", dp(28))
            setPadding(dp(16), dp(14), dp(12), dp(8))
            translationY = screenHeight
        }

        val pillHandle = View(this).apply {
            background = makeGlassCard("#55FFFFFF", "#00000000", dp(99))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }
        drawerSheet.addView(pillHandle)

        searchInput = EditText(this).apply {
            hint = "Search apps or calc (e.g. 48*7)..."
            setHintTextColor(Color.parseColor("#77FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = makeGlassCard("#1FFFFFFF", "#22FFFFFF", dp(16))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterApps(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        drawerSheet.addView(searchInput)

        calcResultPill = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            background = makeGlassCard("#33D71921", "#66D71921", dp(14))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        attachPressFeedback(calcResultPill) {
            val res = calcResultPill.tag?.toString() ?: return@attachPressFeedback
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Calc", res))
            Toast.makeText(this, "Copied $res", Toast.LENGTH_SHORT).show()
        }
        drawerSheet.addView(calcResultPill)

        val drawerBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        gridAdapter = AppGridAdapter()
        appGrid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(14)
            horizontalSpacing = dp(8)
            setPadding(0, dp(16), 0, dp(24))
            clipToPadding = false
            adapter = gridAdapter
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {
                    if (scrollState != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        hideKeyboard()
                        if (contextMenuLayer.childCount > 0) contextMenuLayer.removeAllViews()
                    }
                }
                override fun onScroll(v: android.widget.AbsListView?, f: Int, vI: Int, t: Int) {}
            })
        }

        drawerBody.addView(appGrid)
        drawerBody.addView(buildAlphabetRail())
        drawerSheet.addView(drawerBody)

        contextMenuLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        rootLayout.addView(homeLayer)
        rootLayout.addView(scrimView)
        rootLayout.addView(drawerSheet)
        rootLayout.addView(contextMenuLayer)
        setContentView(rootLayout)
    }

    private fun buildIconDock(): LinearLayout {
        val dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = makeGlassCard("#D9101014", "#33FFFFFF", dp(28))
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }

        val dockTargets = listOf(
            Triple("Pay/QR", "com.fampay.in", "FamApp"),
            Triple("WhatsApp", "com.whatsapp", "WhatsApp"),
            Triple("LocalSend", "org.localsend.localsend_app", "IP Webcam"),
            Triple("Camera", "com.sec.android.app.camera", "Gallery"),
            Triple("Phone", "com.samsung.android.dialer", "Messages")
        )

        for ((name, primaryPkg, altLabel) in dockTargets) {
            val slot = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
                setOnLongClickListener {
                    vibrateTick(12)
                    val altApp = allApps.firstOrNull { it.label.contains(altLabel, ignoreCase = true) }
                    if (altApp != null) launchPackage(altApp.pkg) else launchPackage(primaryPkg)
                    true
                }
            }

            val iconView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
            }
            dockIconViews[primaryPkg] = iconView

            val unreadDot = View(this).apply {
                visibility = View.GONE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#D71921"))
                    setStroke(dp(1), Color.parseColor("#101014"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    marginEnd = dp(10)
                }
            }
            dockBadgeViews[primaryPkg] = unreadDot

            val altDot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#55FFFFFF"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(4), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(2)
                }
            }

            slot.addView(iconView)
            slot.addView(unreadDot)
            slot.addView(altDot)

            attachPressFeedback(slot) {
                val found = allApps.firstOrNull {
                    it.pkg == primaryPkg || it.label.contains(name.substringBefore("/"), ignoreCase = true)
                }
                launchPackage(found?.pkg ?: primaryPkg)
            }
            dockRow.addView(slot)
        }
        return dockRow
    }

    private fun refreshDockIcons() {
        val labelMap = mapOf(
            "com.fampay.in" to "Fam",
            "com.whatsapp" to "WhatsApp",
            "org.localsend.localsend_app" to "LocalSend",
            "com.sec.android.app.camera" to "Camera",
            "com.samsung.android.dialer" to "Phone",
            "com.samsung.android.contacts" to "Contacts"
        )
        for ((pkg, imgView) in dockIconViews) {
            val fallbackLabel = labelMap[pkg] ?: ""
            val match = allApps.firstOrNull {
                it.pkg == pkg || (fallbackLabel.isNotEmpty() && it.label.contains(fallbackLabel, ignoreCase = true))
            }
            if (match != null) {
                imgView.setImageDrawable(match.icon)
                val unread = MediaTurntableService.unreadCounts[match.pkg] ?: 0
                dockBadgeViews[pkg]?.visibility = if (unread > 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun attachPressFeedback(view: View, onClick: () -> Unit) {
        var startX = 0f
        var startY = 0f
        var isCancelled = false

        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    isCancelled = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isCancelled && (abs(ev.rawX - startX) > touchSlop || abs(ev.rawY - startY) > touchSlop)) {
                        isCancelled = true
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!isCancelled) {
                        vibrateTick(5)
                        onClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun showContextMenu(item: AppEntry, rawX: Float, rawY: Float) {
        contextMenuLayer.removeAllViews()
        hideKeyboard()
        vibrateTick(14)

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeGlassCard("#F2121216", "#44FFFFFF", dp(16))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(8).toFloat()
        }

        val infoBtn = createMenuButton("App Info", "#FFFFFF") {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${item.pkg}"))
            startActivity(intent)
            contextMenuLayer.removeAllViews()
        }
        val hideBtn = createMenuButton("Hide App", "#FFFFFF") {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hidden = prefs.getStringSet("hidden_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            hidden.add(item.pkg)
            prefs.edit().putStringSet("hidden_apps", hidden).apply()

            contextMenuLayer.removeAllViews()
            loadInstalledAppsAsync()
            Toast.makeText(this, "${item.label} hidden", Toast.LENGTH_SHORT).show()
        }
        val uninstallBtn = createMenuButton("Uninstall", "#FF6B70") {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.pkg}"))
            startActivity(intent)
            contextMenuLayer.removeAllViews()
        }

        menuCard.addView(infoBtn)
        menuCard.addView(hideBtn)
        menuCard.addView(uninstallBtn)

        val cardWidth = dp(160)
        val cardHeight = dp(150)

        var targetX = rawX - (cardWidth / 2)
        var targetY = rawY - cardHeight - dp(24)

        if (targetX < dp(16)) targetX = dp(16).toFloat()
        if (targetX + cardWidth > screenWidth - dp(16)) targetX = screenWidth - cardWidth - dp(16)
        if (targetY < dp(32)) targetY = rawY + dp(24)

        menuCard.x = targetX
        menuCard.y = targetY
        menuCard.scaleX = 0.8f
        menuCard.scaleY = 0.8f
        menuCard.alpha = 0f
        menuCard.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start()

        contextMenuLayer.addView(menuCard, FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun createMenuButton(textLabel: String, colorHex: String, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = textLabel
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        attachPressFeedback(tv, onClick)
        return tv
    }

    private fun buildAlphabetRail(): LinearLayout {
        val letters = ('A'..'Z').toList()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(16), dp(4), dp(24))
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT)

            for (ch in letters) {
                addView(TextView(context).apply {
                    text = ch.toString()
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                })
            }
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        val ratio = (ev.y / v.height.coerceAtLeast(1)).coerceIn(0f, 0.999f)
                        val targetChar = letters[(ratio * letters.size).toInt()]
                        val idx = filteredApps.indexOfFirst {
                            it.label.uppercase(Locale.getDefault()).startsWith(targetChar)
                        }
                        if (idx >= 0 && appGrid.firstVisiblePosition != idx) {
                            vibrateTick(4)
                            appGrid.setSelection(idx)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun restoreBoundWidget() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getInt(KEY_WIDGET_ID, -1)
        if (savedId != -1) attachWidgetView(savedId) else showBindWidgetPrompt()
    }

    private fun showBindWidgetPrompt() {
        widgetSlotContainer.removeAllViews()
        val prompt = TextView(this).apply {
            text = "+ TAP TO BIND MATIKS WIDGET\n(Long-press anytime to swap)"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#A8C7FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        attachPressFeedback(prompt) { launchWidgetPicker() }
        widgetSlotContainer.addView(prompt)
    }

    private fun launchWidgetPicker() {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        startActivityForResult(pickIntent, REQ_PICK_WIDGET)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (resultCode != RESULT_OK || widgetId == -1) {
            if (widgetId != -1) appWidgetHost.deleteAppWidgetId(widgetId)
            return
        }
        if (requestCode == REQ_PICK_WIDGET) {
            val info = appWidgetManager.getAppWidgetInfo(widgetId)
            if (info?.configure != null) {
                val cfgIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                startActivityForResult(cfgIntent, REQ_CREATE_WIDGET)
            } else {
                saveAndAttachWidget(widgetId)
            }
        } else if (requestCode == REQ_CREATE_WIDGET) {
            saveAndAttachWidget(widgetId)
        }
    }

    private fun saveAndAttachWidget(widgetId: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_WIDGET_ID, widgetId).apply()
        attachWidgetView(widgetId)
    }

    private fun attachWidgetView(widgetId: Int) {
        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        if (info == null) {
            showBindWidgetPrompt()
            return
        }
        widgetSlotContainer.removeAllViews()
        val hostView = appWidgetHost.createView(applicationContext, widgetId, info).apply {
            setPadding(0, 0, 0, 0)
            setOnLongClickListener {
                vibrateTick(12)
                launchWidgetPicker()
                true
            }
        }
        widgetSlotContainer.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun handleGestureIntercept(ev: MotionEvent): Boolean {
        if (contextMenuLayer.childCount > 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                isDraggingDrawer = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val drawerClosed = drawerSpring.pos > screenHeight * 0.5f
                val gridAtTop = !appGrid.canScrollVertically(-1)

                if (drawerClosed && abs(dy) > touchSlop) {
                    if (dy > touchSlop * 1.4f) {
                        expandStatusBar()
                        return false
                    }
                    startDrawerDrag(ev.rawY)
                    return true
                } else if (!drawerClosed && dy > touchSlop && gridAtTop) {
                    hideKeyboard()
                    startDrawerDrag(ev.rawY)
                    return true
                }
            }
        }
        return false
    }

    private fun startDrawerDrag(rawY: Float) {
        isDraggingDrawer = true
        drawerSpring.grab(drawerSheet.translationY)
        grabOffsetY = rawY - drawerSpring.pos
        vibrateTick(6)
    }

    private fun handleGestureTouch(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingDrawer && abs(ev.rawY - downY) > touchSlop) {
                    startDrawerDrag(ev.rawY)
                }
                if (isDraggingDrawer) {
                    var targetY = ev.rawY - grabOffsetY
                    if (targetY < 0f) {
                        targetY = -FluidSpring.rubberband(-targetY, screenHeight)
                    } else if (targetY > screenHeight) {
                        targetY = screenHeight + FluidSpring.rubberband(targetY - screenHeight, screenHeight)
                    }
                    drawerSpring.pos = targetY
                    applyDrawerFrame(targetY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingDrawer) {
                    isDraggingDrawer = false
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    val projected = drawerSpring.pos + FluidSpring.project(vy, 0.998f)
                    val finalTarget = if (projected < screenHeight * 0.52f) 0f else screenHeight

                    if (finalTarget > 0f) hideKeyboard()
                    vibrateTick(8)
                    drawerSpring.animateTo(finalTarget, vy, 0.82f, 0.32f) { y ->
                        applyDrawerFrame(y)
                        // Auto-keyboard when drawer snaps open
                        if (y == 0f) {
                            searchInput.requestFocus()
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    private fun applyDrawerFrame(y: Float) {
        drawerSheet.translationY = y
        val progress = (1f - (y / screenHeight)).coerceIn(0f, 1f)
        scrimView.alpha = progress * 0.82f
        val scale = 1f - (progress * 0.06f)
        homeLayer.scaleX = scale
        homeLayer.scaleY = scale
        homeLayer.alpha = 1f - (progress * 0.4f)
    }

    private fun loadInstalledAppsAsync() {
        thread {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val loaded = pm.queryIntentActivities(mainIntent, 0)
                .mapNotNull { info ->
                    val pkg = info.activityInfo.packageName
                    if (pkg == packageName) return@mapNotNull null
                    AppEntry(label = info.loadLabel(pm).toString(), pkg = pkg, icon = info.loadIcon(pm))
                }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }

            runOnUiThread {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val hiddenPkgs = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
                appGrid.numColumns = prefs.getInt("grid_columns", 4)

                allApps.clear()
                allApps.addAll(loaded.filter { it.pkg !in hiddenPkgs })
                filterApps(searchInput.text.toString())
                refreshDockIcons()
            }
        }
    }

    private fun filterApps(query: String) {
        filteredApps.clear()
        val clean = query.trim().lowercase(Locale.getDefault())
        if (clean.isEmpty()) {
            filteredApps.addAll(allApps)
            calcResultPill.visibility = View.GONE
        } else {
            filteredApps.addAll(allApps.filter { it.label.lowercase(Locale.getDefault()).contains(clean) })
            val mathVal = evalSimpleMath(clean)
            if (mathVal != null) {
                calcResultPill.tag = mathVal
                calcResultPill.text = "= $mathVal   [TAP TO COPY]"
                calcResultPill.visibility = View.VISIBLE
            } else {
                calcResultPill.visibility = View.GONE
            }
        }
        gridAdapter.notifyDataSetChanged()
    }

    private fun evalSimpleMath(expr: String): String? {
        val m = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/%])\\s*(-?\\d+(?:\\.\\d+)?)$").find(expr) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val op = m.groupValues[2]
        val b = m.groupValues[3].toDoubleOrNull() ?: return null
        val res = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b != 0.0) a / b else return null
            "%" -> if (b != 0.0) a % b else return null
            else -> return null
        }
        return if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.3f", res)
    }

    //huhhhhhhhhhhhhhhhhhhhhhhhhhhh
    private fun updateHeaderTelemetry() {
        val now = Date()
        clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val dateStr = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
        val mobileMb = (TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()) / (1024 * 1024)
        val edgeState = if (EdgeGestureService.instance != null) "EDGE ON" else "ENABLE EDGE"
        subHeaderText.text = "$dateStr • ${mobileMb}MB • $edgeState"

        val code = MediaTurntableService.latestOtp
        if (code != null) {
            otpPill.text = "COPY OTP // $code"
            otpPill.visibility = View.VISIBLE
        } else {
            otpPill.visibility = View.GONE
        }
        refreshDockIcons()
        gridAdapter.notifyDataSetChanged()
    }

    private fun launchPackage(pkg: String) {
        if (contextMenuLayer.childCount > 0) contextMenuLayer.removeAllViews()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun expandStatusBar() {
        try {
            val service = getSystemService("statusbar")
            val clazz = Class.forName("android.app.StatusBarManager")
            clazz.getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrateTick(ms: Long) {
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(ms)
    }

    private fun makeGlassCard(fillHex: String, strokeHex: String, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = radiusPx.toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (contextMenuLayer.childCount > 0) {
            contextMenuLayer.removeAllViews()
        } else if (drawerSpring.pos < screenHeight * 0.5f) {
            hideKeyboard()
            drawerSpring.animateTo(screenHeight, 0f) { y -> applyDrawerFrame(y) }
        }
    }

    private inner class AppGridAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredApps.size
        override fun getItem(position: Int): Any = filteredApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = filteredApps[position]

            // Use a FrameLayout wrapper inside the adapter so we can overlay the unread dot badge on the icon
            val box = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                setPadding(dp(4), dp(8), dp(4), dp(8))

                val innerLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    addView(ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })
                    addView(TextView(context).apply {
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(0, dp(6), 0, 0)
                    })
                }
                addView(innerLayout)

                // Nothing-red unread notification dot badge (top-right of app icon)
                val unreadDot = View(context).apply {
                    id = 999 // Tag ID to look up later
                    visibility = View.GONE
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#D71921"))
                        setStroke(dp(1), Color.parseColor("#101014"))
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(4)
                        marginEnd = dp(8)
                    }
                }
                addView(unreadDot)
            }

            val innerLayout = box.getChildAt(0) as LinearLayout
            val iconView = innerLayout.getChildAt(0) as ImageView
            val labelView = innerLayout.getChildAt(1) as TextView
            val unreadDot = box.findViewById<View>(999)

            iconView.setImageDrawable(item.icon)
            labelView.text = item.label
            labelView.setTextColor(Color.WHITE)

            // Check unread count from our NotificationListenerService
            val unread = MediaTurntableService.unreadCounts[item.pkg] ?: 0
            unreadDot.visibility = if (unread > 0) View.VISIBLE else View.GONE

            var startX = 0f
            var startY = 0f
            var isCancelled = false

            box.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = ev.rawX; startY = ev.rawY; isCancelled = false
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isCancelled && (abs(ev.rawX - startX) > touchSlop || abs(ev.rawY - startY) > touchSlop)) {
                            isCancelled = true
                            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        }
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        false
                    }
                    else -> false
                }
            }

            box.setOnClickListener {
                if (!isCancelled) launchPackage(item.pkg)
            }

            box.setOnLongClickListener {
                if (!isCancelled) {
                    val location = IntArray(2)
                    box.getLocationOnScreen(location)
                    showContextMenu(item, location[0].toFloat() + (box.width / 2), location[1].toFloat())
                }
                true
            }

            return box
        }
    }
}