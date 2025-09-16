package com.buligin.vishnucast

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.Keep
import kotlin.math.min

class HintArrowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var anim: ValueAnimator? = null
    private var phase = 0f           // 0..1 для покачивания
    private var alphaFrac = 1f       // 0.6..1
    private var leftDirection = false

    fun setDirectionLeft(left: Boolean) {
        leftDirection = left
        invalidate()
    }

    fun startHint() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedFraction
                // альфа 0.6..1.0
                alphaFrac = 0.6f + 0.4f * phase
                invalidate()
            }
            start()
        }
        visibility = VISIBLE
    }

    fun stopHint() {
        anim?.cancel(); anim = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel(); anim = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return

        // размеры стрелки относительно высоты «пилюли»
        val h = height.toFloat()
        val w = width.toFloat()
        val size = min(h * 0.42f, dp(28f))        // длина «галочки»
        paint.strokeWidth = h * 0.12f             // толщина линий
        paint.alpha = (alphaFrac * 255).toInt()

        // центр
        val cx = w / 2f
        val cy = h / 2f

        // покачивание вправо/влево ~10dp
        val sway = dp(10f) * (if (leftDirection) -1f else 1f)
        val dx = (phase - 0.5f) * 2f * sway
        canvas.save()
        canvas.translate(cx + dx, cy)

        // рисуем «галочку»: две линии
        if (leftDirection) {
            // «←» (отражённая галочка)
            // точки: вправо-вниз и вправо-вверх
            canvas.drawLine(+size * 0.6f, -size, 0f, 0f, paint)
            canvas.drawLine(+size * 0.6f, +size, 0f, 0f, paint)
        } else {
            // «→»
            canvas.drawLine(-size * 0.6f, -size, 0f, 0f, paint)
            canvas.drawLine(-size * 0.6f, +size, 0f, 0f, paint)
        }
        canvas.restore()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
