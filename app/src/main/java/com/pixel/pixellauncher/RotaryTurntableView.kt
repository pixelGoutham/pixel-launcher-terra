package com.pixel.pixellauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Vibrator
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class RotaryTurntableView(
    context: Context,
    private val onLaunchPackage: (String) -> Unit,
    private val onRequestPermission: () -> Unit
) : View(context) {

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D9121216") }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#33FFFFFF")
    }
    private val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E1E24") }
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#2EFFFFFF")
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D71921") }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E4E4EA")
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9A9AA6")
        textSize = sp(12f)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8C7FA")
        textSize = sp(10.5f)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.08f
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()

    private val cardRect = RectF()
    private val eqPillRect = RectF()
    private var discCx = 0f
    private var discCy = 0f
    private var discRadius = 0f

    private var continuousSpinDeg = 0f
    private var scrubOffsetDeg = 0f
    private var armAngleDeg = -26f
    private val scrubSpring = FluidSpring(0f)
    private var isScrubbingDisc = false
    private var grabStartAngle = 0f
    private var downTime = 0L
    private var committedDetent = 0
    private var discScale = 1f

    private var cachedArt: Bitmap? = null
    private var artShader: BitmapShader? = null

    private val spinCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val playing = MediaTurntableService.isPlaying
            val targetArm = if (playing && !isScrubbingDisc) 0f else -26f
            val armMoving = abs(armAngleDeg - targetArm) > 0.2f
            if (armMoving) {
                armAngleDeg += (targetArm - armAngleDeg) * 0.14f
            }
            if ((playing && !isScrubbingDisc) || armMoving) {
                if (playing && !isScrubbingDisc) {
                    continuousSpinDeg = (continuousSpinDeg + 0.9f) % 360f
                }
                invalidate()
            }
            if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        MediaTurntableService.onStateChanged = {
            post {
                updateAlbumShader(MediaTurntableService.albumArt)
                invalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(spinCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(spinCallback)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(134f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cardRect.set(0f, 0f, w.toFloat(), h.toFloat())
        discRadius = h * 0.38f
        discCx = dp(16f) + discRadius
        discCy = h * 0.5f
        eqPillRect.set(w - dp(88f), h - dp(38f), w - dp(14f), h - dp(12f))
    }

    private fun updateAlbumShader(bmp: Bitmap?) {
        if (bmp == null) {
            cachedArt = null
            artShader = null
            return
        }
        if (bmp != cachedArt) {
            cachedArt = bmp
            artShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            artPaint.shader = artShader
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(cardRect, dp(24f), dp(24f), cardPaint)
        canvas.drawRoundRect(cardRect, dp(24f), dp(24f), strokePaint)

        canvas.save()
        canvas.scale(discScale, discScale, discCx, discCy)
        canvas.rotate(continuousSpinDeg + scrubOffsetDeg, discCx, discCy)
        canvas.drawCircle(discCx, discCy, discRadius, vinylPaint)

        val shader = artShader
        val bmp = cachedArt
        if (shader != null && bmp != null) {
            val scale = (discRadius * 1.65f) / bmp.width.coerceAtLeast(1)
            shaderMatrix.reset()
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(discCx - discRadius * 0.82f, discCy - discRadius * 0.82f)
            shader.setLocalMatrix(shaderMatrix)
            canvas.drawCircle(discCx, discCy, discRadius * 0.82f, artPaint)
        } else {
            canvas.drawCircle(discCx, discCy, discRadius * 0.72f, groovePaint)
            canvas.drawCircle(discCx, discCy, discRadius * 0.48f, groovePaint)
        }

        for (i in 0 until 12) {
            val rad = Math.toRadians((i * 30).toDouble())
            val px = discCx + (discRadius * 0.9f) * cos(rad).toFloat()
            val py = discCy + (discRadius * 0.9f) * sin(rad).toFloat()
            canvas.drawCircle(px, py, if (i == 0) dp(3.2f) else dp(1.6f), if (i == 0) accentPaint else strokePaint)
        }

        canvas.drawCircle(discCx, discCy, discRadius * 0.22f, cardPaint)
        canvas.drawCircle(discCx, discCy, discRadius * 0.09f, accentPaint)
        canvas.restore()

        val pivotX = discCx + discRadius * 0.92f
        val pivotY = discCy - discRadius * 0.85f
        canvas.save()
        canvas.rotate(armAngleDeg, pivotX, pivotY)
        canvas.drawLine(pivotX, pivotY, pivotX - discRadius * 0.28f, pivotY + discRadius * 1.15f, armPaint)
        canvas.drawCircle(pivotX - discRadius * 0.28f, pivotY + discRadius * 1.15f, dp(3f), accentPaint)
        canvas.restore()
        canvas.drawCircle(pivotX, pivotY, dp(5.5f), vinylPaint)
        canvas.drawCircle(pivotX, pivotY, dp(2.5f), strokePaint)

        val textLeft = discCx + discRadius + dp(16f)
        val maxTextWidth = width - textLeft - dp(16f)

        val statusLabel = when (committedDetent) {
            1 -> ">> RELEASE FOR NEXT"
            -1 -> "<< RELEASE FOR PREV"
            else -> if (MediaTurntableService.isPlaying) "SPINNING // YT MUSIC" else "ROTATE DISC // PLAY"
        }
        monoPaint.color = if (committedDetent != 0) Color.parseColor("#D71921") else Color.parseColor("#A8C7FA")
        canvas.drawText(statusLabel, textLeft, dp(32f), monoPaint)

        canvas.drawText(ellipsize(MediaTurntableService.trackTitle, titlePaint, maxTextWidth), textLeft, dp(58f), titlePaint)
        canvas.drawText(ellipsize(MediaTurntableService.trackArtist, subPaint, maxTextWidth), textLeft, dp(80f), subPaint)

        val eqStartX = textLeft
        val eqBaseY = height - dp(16f)
        for (col in 0 until 8) {
            val activeDots = if (MediaTurntableService.isPlaying) {
                val wave = sin(Math.toRadians((continuousSpinDeg * 4f + col * 48f).toDouble()))
                (2 + ((wave + 1.0) * 2.0).toInt()).coerceIn(1, 5)
            } else {
                1
            }
            for (row in 0 until 5) {
                val lit = row < activeDots
                accentPaint.color = if (lit) Color.parseColor("#D71921") else Color.parseColor("#26FFFFFF")
                canvas.drawCircle(
                    eqStartX + col * dp(7f),
                    eqBaseY - row * dp(5f),
                    dp(1.7f),
                    accentPaint
                )
            }
        }
        accentPaint.color = Color.parseColor("#D71921")

        canvas.drawRoundRect(eqPillRect, dp(99f), dp(99f), vinylPaint)
        canvas.drawRoundRect(eqPillRect, dp(99f), dp(99f), strokePaint)
        monoPaint.color = Color.WHITE
        canvas.drawText("EQ //FX", eqPillRect.left + dp(12f), eqPillRect.centerY() + dp(3.5f), monoPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                if (eqPillRect.contains(x, y)) {
                    vibrate(6)
                    onLaunchPackage("spigen")
                    return true
                }
                if (hypot(x - discCx, y - discCy) <= discRadius * 1.25f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    isScrubbingDisc = true
                    discScale = 0.95f
                    scrubSpring.grab(scrubOffsetDeg)
                    grabStartAngle = angleFromCenter(x, y) - scrubOffsetDeg
                    committedDetent = 0
                    vibrate(5)
                    invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScrubbingDisc) {
                    var delta = normalizeDelta(angleFromCenter(x, y) - grabStartAngle)
                    val threshold = 32f
                    if (abs(delta) > threshold) {
                        val sign = if (delta > 0) 1f else -1f
                        delta = sign * (threshold + FluidSpring.rubberband(abs(delta) - threshold, 60f, 0.45f))
                        val newDetent = if (delta > 0) 1 else -1
                        if (committedDetent != newDetent) {
                            committedDetent = newDetent
                            vibrate(10)
                        }
                    } else if (committedDetent != 0) {
                        committedDetent = 0
                        vibrate(4)
                    }
                    scrubOffsetDeg = delta
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                discScale = 1f
                if (isScrubbingDisc) {
                    isScrubbingDisc = false
                    val elapsed = System.currentTimeMillis() - downTime
                    if (committedDetent == 1) {
                        if (!MediaTurntableService.skipNext()) onLaunchPackage("com.google.android.apps.youtube.music")
                    } else if (committedDetent == -1) {
                        if (!MediaTurntableService.skipPrev()) onLaunchPackage("com.google.android.apps.youtube.music")
                    } else if (elapsed < 230 && abs(scrubOffsetDeg) < 9f) {
                        vibrate(8)
                        if (MediaTurntableService.instance == null) {
                            onRequestPermission()
                        } else if (!MediaTurntableService.togglePlayPause()) {
                            onLaunchPackage("com.google.android.apps.youtube.music")
                        }
                    }
                    committedDetent = 0
                    scrubSpring.animateTo(0f, 0f, dampingRatio = 0.78f, responseSec = 0.32f) { valDeg ->
                        scrubOffsetDeg = valDeg
                        invalidate()
                    }
                } else if (System.currentTimeMillis() - downTime < 230) {
                    onLaunchPackage("com.google.android.apps.youtube.music")
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun angleFromCenter(px: Float, py: Float): Float =
        Math.toDegrees(atan2((py - discCy).toDouble(), (px - discCx).toDouble())).toFloat()

    private fun normalizeDelta(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun ellipsize(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > maxW) end--
        return text.substring(0, end) + "…"
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(ms)
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}