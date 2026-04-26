package com.seekaudio.ui.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.seekaudio.R

/**
 * Custom view that draws an animated waveform (3 bars bouncing at different speeds).
 * Used in the song list to indicate the currently playing track.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.purple_primary)
        style = Paint.Style.FILL
    }

    private val barCount  = 3
    private val barGap    = 3f
    private val animators = mutableListOf<ValueAnimator>()
    private val heights   = FloatArray(barCount) { 0.3f }

    init {
        startAnimations()
    }

    private fun startAnimations() {
        stopAnimations()
        val speeds = longArrayOf(600, 800, 700)
        for (i in 0 until barCount) {
            val anim = ValueAnimator.ofFloat(0.2f, 1.0f).apply {
                duration       = speeds[i]
                repeatCount    = ValueAnimator.INFINITE
                repeatMode     = ValueAnimator.REVERSE
                startDelay     = (i * 120).toLong()
                addUpdateListener { animator ->
                    heights[i] = animator.animatedValue as Float
                    invalidate()
                }
            }
            anim.start()
            animators.add(anim)
        }
    }

    private fun stopAnimations() {
        animators.forEach { it.cancel() }
        animators.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w       = width.toFloat()
        val h       = height.toFloat()
        val barW    = (w - barGap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val left   = i * (barW + barGap)
            val barH   = h * heights[i]
            val top    = h - barH
            val radius = barW / 2f
            canvas.drawRoundRect(left, top, left + barW, h, radius, radius, paint)
        }
    }

    override fun onAttachedToWindow()  { super.onAttachedToWindow();  startAnimations() }
    override fun onDetachedFromWindow(){ super.onDetachedFromWindow(); stopAnimations()  }
}
