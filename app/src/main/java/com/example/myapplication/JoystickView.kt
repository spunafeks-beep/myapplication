package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val outerPaint = Paint().apply {
        color = Color.parseColor("#40FFFFFF") // Белый, очень прозрачный (фон джойстика)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val innerPaint = Paint().apply {
        color = Color.parseColor("#AAFFFFFF") // Почти белый (сама ручка)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var hatRadius = 0f
    private var joyX = 0f
    private var joyY = 0f

    // Слушатель: (x: Int, y: Int) -> значения от -100 до 100
    var onMoveListener: ((Int, Int) -> Unit)? = null
    private val interval = 50L // Ограничение частоты отправки (мс)
    private var lastSendTime = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = width / 2f
        centerY = height / 2f
        val d = min(w, h)
        baseRadius = d / 2f * 0.9f // База чуть меньше области
        hatRadius = d / 2f * 0.3f  // Ручка 30% от размера
        resetJoystick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Рисуем базу
        canvas.drawCircle(centerX, centerY, baseRadius, outerPaint)
        // Рисуем ручку
        canvas.drawCircle(joyX, joyY, hatRadius, innerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val distance = hypot(dx, dy)

                if (distance > baseRadius) {
                    val ratio = baseRadius / distance
                    joyX = centerX + dx * ratio
                    joyY = centerY + dy * ratio
                } else {
                    joyX = event.x
                    joyY = event.y
                }

                val rawX = joyX - centerX
                val rawY = joyY - centerY

// Преобразуем в проценты (-100..100)
// Для X: вправо — это плюс, влево — минус.
// Для Y: вверх — это плюс, вниз — минус (поэтому инвертируем через минус).
                val percentX = (rawX / baseRadius * 100).toInt().coerceIn(-100, 100)
                val percentY = -(rawY / baseRadius * 100).toInt().coerceIn(-100, 100)

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSendTime > interval) {
                    // ПЕРЕДАЕМ ПАРАМЕТРЫ: сначала X, потом Y
                    onMoveListener?.invoke(percentX, percentY)
                    lastSendTime = currentTime
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetJoystick()
                onMoveListener?.invoke(0, 0)
            }
        }
        invalidate()
        return true
    }

    private fun resetJoystick() {
        joyX = centerX
        joyY = centerY
    }
}