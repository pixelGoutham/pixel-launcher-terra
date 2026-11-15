package com.pixel.pixellauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class EdgeGestureService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var leftEdgeStrip: View? = null
    private var rightEdgeStrip: View? = null
    private var visualCanvasView: EdgeBubbleView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setupEdgeOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeOverlays()
    }

    private fun setupEdgeOverlays() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val dm = resources.displayMetrics
        val stripHeight = (dm.heightPixels * 0.65f).toInt()
        val stripWidth = dp(18f).toInt()

        val feedbackView = EdgeBubbleView(this)
        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(feedbackView, feedbackParams)
        visualCanvasView = feedbackView

        leftEdgeStrip = createTouchStrip(isLeft = true)
        rightEdgeStrip = createTouchStrip(isLeft = false)

        wm.addView(leftEdgeStrip, makeEdgeLayoutParams(stripWidth, stripHeight, Gravity.START or Gravity.BOTTOM))
        wm.addView(rightEdgeStrip, makeEdgeLayoutParams(stripWidth, stripHeight, Gravity.END or Gravity.BOTTOM))
    }

    private fun makeEdgeLayoutParams(w: Int, h: Int, gravity: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }
    }

    private fun createTouchStrip(isLeft: Boolean): View {
        return View(this).apply {
            val handler = Handler(Looper.getMainLooper())
            var downX = 0f
            var downY = 0f
            var committedBack = false
            var committedRecents = false

            val recentsRunnable = Runnable {
                if (committedBack && !committedRecents) {
                    committedRecents = true
                    vibrate(14)
                    visualCanvasView?.setMode(recents = true)
                }
            }

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        committedBack = false
                        committedRecents = false
                        visualCanvasView?.onGrab(isLeft, downY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val rawPull = if (isLeft) (ev.rawX - downX) else (downX - ev.rawX)
                        val pull = rawPull.coerceAtLeast(0f)
                        val threshold = dp(42f)

                        val displayPull = if (pull <= threshold) {
                            pull
                        } else {
                            threshold + FluidSpring.rubberband(pull - threshold, dp(95f), 0.5f)
                        }

                        if (pull >= threshold && !committedBack) {
                            committedBack = true
                            vibrate(8)
                            handler.postDelayed(recentsRunnable, 240L)
                        } else if (pull < threshold * 0.75f && committedBack) {
                            committedBack = false
                            committedRecents = false
                            handler.removeCallbacks(recentsRunnable)
                            visualCanvasView?.setMode(recents = false)
                            vibrate(4)
                        }

                        visualCanvasView?.onPullUpdate(displayPull, ev.rawY, committedBack)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(recentsRunnable)
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            if (committedRecents) {
                                performGlobalAction(GLOBAL_ACTION_RECENTS)
                            } else if (committedBack) {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                            }
                        }
                        visualCanvasView?.onRelease()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun removeOverlays() {
        val wm = windowManager ?: return
        listOfNotNull(leftEdgeStrip, rightEdgeStrip, visualCanvasView).forEach { view ->
            try {
                wm.removeView(view)
            } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(ms)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        var instance: EdgeGestureService? = null
    }

    private inner class EdgeBubbleView(context: Context) : View(context) {
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6121216")
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.2f)
            color = Color.parseColor("#44FFFFFF")
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D71921")
            textSize = dp(10f)
            typeface = Typeface.MONOSPACE
        }

        private val pillRect = RectF()
        private val pullSpring = FluidSpring(0f)
        private var isLeftEdge = true
        private var anchorY = 0f
        private var currentPull = 0f
        private var isCommitted = false
        private var isRecentsMode = false

        fun onGrab(left: Boolean, rawY: Float) {
            isLeftEdge = left
            anchorY = rawY
            isCommitted = false
            isRecentsMode = false
            pullSpring.grab(currentPull)
            invalidate()
        }

        fun onPullUpdate(pullPx: Float, rawY: Float, committed: Boolean) {
            currentPull = pullPx
            anchorY += (rawY - anchorY) * 0.25f
            isCommitted = committed
            invalidate()
        }

        fun setMode(recents: Boolean) {
            isRecentsMode = recents
            invalidate()
        }

        fun onRelease() {
            pullSpring.grab(currentPull)
            pullSpring.animateTo(0f, 0f, dampingRatio = 0.85f, responseSec = 0.25f) { valPx ->
                currentPull = valPx
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (currentPull < 2f) return

            val pillHalfH = dp(42f)
            val widthPx = currentPull.coerceAtMost(dp(64f))
            val left = if (isLeftEdge) -dp(14f) else width - widthPx + dp(14f)
            val right = if (isLeftEdge) widthPx else width + dp(14f)

            pillRect.set(left, anchorY - pillHalfH, right, anchorY + pillHalfH)
            canvas.drawRoundRect(pillRect, dp(28f), dp(28f), pillPaint)
            rimPaint.color = if (isRecentsMode) Color.parseColor("#D71921") else Color.parseColor("#44FFFFFF")
            canvas.drawRoundRect(pillRect, dp(28f), dp(28f), rimPaint)

            dotPaint.color = when {
                isRecentsMode -> Color.parseColor("#D71921")
                isCommitted -> Color.WHITE
                else -> Color.parseColor("#88FFFFFF")
            }

            val cx = if (isLeftEdge) (right - dp(16f)) else (left + dp(16f))
            val cy = anchorY
            val dir = if (isLeftEdge) 1f else -1f
            val step = dp(4.5f)
            val r = dp(1.8f)

            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx - dir * step, cy - step, r, dotPaint)
            canvas.drawCircle(cx - dir * step, cy + step, r, dotPaint)
            canvas.drawCircle(cx - dir * step * 2f, cy - step * 2f, r, dotPaint)
            canvas.drawCircle(cx - dir * step * 2f, cy + step * 2f, r, dotPaint)

            if (isRecentsMode) {
                val tx = if (isLeftEdge) right + dp(8f) else left - dp(56f)
                canvas.drawText("RECENTS", tx, cy + dp(4f), labelPaint)
            }
        }
    }
}