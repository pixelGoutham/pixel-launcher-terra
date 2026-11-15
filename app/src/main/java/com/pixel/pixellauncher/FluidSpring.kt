package com.pixel.pixellauncher
import android.view.Choreographer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log
import kotlin.math.min

class FluidSpring(initialPos: Float = 0f) {
    var pos: Float = initialPos
    var vel: Float = 0f
    var target: Float = initialPos
    private var running = false
    private var lastFrameNanos = 0L
    private var damping = 1.0f
    private var response = 0.35f
    private var onUpdate: ((Float) -> Unit)? = null

    private var frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.032f)
            lastFrameNanos = frameTimeNanos

            val omega = (2.9 * PI / response).toFloat()
            val disp = pos - target
            val accel = -omega * omega * disp -2f * damping * omega * vel

            vel += accel*dt
            pos += vel*dt
            onUpdate?.invoke(pos)

            if (abs(disp) < 0.5f && abs(vel) < 6f) {
                pos = target
                vel = 0f
                running =false
                onUpdate?.invoke(pos)
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun grab(livePos: Float = pos) {
        if (running) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            running = false
        }
        pos = livePos
        vel = 0f
    }
    fun animateTo(
        nextTarget: Float,
        releaseVel: Float = vel,
        dampingRatio: Float = 0.82f,
        responseSec: Float = 0.32f,
        listener: (Float) -> Unit
    ) {
        target = nextTarget
        vel = releaseVel
        damping = dampingRatio
        response = responseSec
        onUpdate = listener

        if (!running) {
            running = true
            lastFrameNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    companion object {
        fun project(initialVel: Float, decel: Float = 0.998f): Float {
            return (initialVel / 1000f) * decel / (1f - decel)
        }

        fun rubberband(overshoot: Float, dim: Float, coeff: Float = 0.55f): Float {
            return (overshoot * dim * coeff) / (dim + coeff * abs(overshoot))
        }
    }
}