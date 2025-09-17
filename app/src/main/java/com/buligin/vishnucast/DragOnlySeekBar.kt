package com.buligin.vishnucast

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatSeekBar
import kotlin.math.abs

/**
 * SeekBar, который реагирует ТОЛЬКО на перетаскивание "шарика" (thumb).
 * Тапы по дорожке не двигают прогресс.
 */
class DragOnlySeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val thumbRect = Rect()

    private var downX = 0f
    private var downY = 0f
    private var isDraggingThumb = false
    private var movedEnough = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // проверяем, попали ли в увеличенную хит-зону "шарика"
                isDraggingThumb = isTouchOnThumb(event.x.toInt(), event.y.toInt())
                movedEnough = false
                downX = event.x
                downY = event.y

                // если палец НЕ на "шарике", просто съедаем событие (никаких скачков прогресса)
                if (!isDraggingThumb) return true

                // перехватываем жест, чтобы родитель (ScrollView и пр.) не вмешивался
                parent?.requestDisallowInterceptTouchEvent(true)
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingThumb) return true
                if (!movedEnough &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    movedEnough = true
                }
                // только при реальном перетаскивании отдаём управлению базового класса
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDraggingThumb
                isDraggingThumb = false
                parent?.requestDisallowInterceptTouchEvent(false)

                return if (wasDragging && movedEnough) {
                    // завершаем drag штатно
                    super.onTouchEvent(event)
                } else {
                    // tap/cancel без перетаскивания — прогресс не меняем
                    isPressed = false
                    refreshDrawableState()
                    true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isTouchOnThumb(x: Int, y: Int): Boolean {
        val th = thumb ?: return false
        // bounds "шарика" уже в координатах вью
        th.copyBounds(thumbRect)
        // Увеличенная хит-зона: 24dp вокруг "шарика"
        val pad = (24f * resources.displayMetrics.density).toInt()
        thumbRect.inset(-pad, -pad)
        return thumbRect.contains(x, y)
    }
}
