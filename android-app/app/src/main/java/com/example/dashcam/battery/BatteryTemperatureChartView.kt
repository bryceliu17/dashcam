package com.example.dashcam.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.example.dashcam.data.BatteryTemperatureSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class BatteryTemperatureChartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Path()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val selectedTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var samples: List<BatteryTemperatureSample> = emptyList()
    private var windowHours = 24
    private var selectedIndex: Int? = null

    init {
        setBackgroundColor(Color.WHITE)
        minimumHeight = (260 * resources.displayMetrics.density).toInt()
    }

    fun setSamples(value: List<BatteryTemperatureSample>, hours: Int) {
        samples = value
        windowHours = hours
        selectedIndex = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (samples.isNotEmpty()) {
                    val density = resources.displayMetrics.density
                    val left = 48f * density
                    val right = width - 12f * density
                    val fraction = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - windowHours * 60L * 60_000L
                    val selectedTime = startTime + ((endTime - startTime) * fraction).toLong()
                    selectedIndex = samples.indices.minByOrNull { index ->
                        kotlin.math.abs(samples[index].recordedAt - selectedTime)
                    }
                    invalidate()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = 48f * density
        val right = width - 12f * density
        val top = 18f * density
        val bottom = height - 34f * density
        if (right <= left || bottom <= top) return
        paint.textSize = 11f * density
        paint.strokeWidth = density
        paint.style = Paint.Style.STROKE
        val values = samples.map { it.temperatureTenthsC / 10f }
        val low = min(20f, (values.minOrNull() ?: 20f) - 2f)
        val high = max(50f, (values.maxOrNull() ?: 45f) + 2f)
        fun y(temp: Float) = bottom - (temp - low) / (high - low) * (bottom - top)
        for (step in 0..4) {
            val temp = low + (high - low) * step / 4f
            val pointY = y(temp)
            paint.color = Color.rgb(226, 232, 240)
            canvas.drawLine(left, pointY, right, pointY, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(71, 85, 105)
            canvas.drawText("${temp.toInt()}°", 8f * density, pointY + 4f * density, paint)
            paint.style = Paint.Style.STROKE
        }
        listOf(40f to Color.rgb(234, 88, 12), 45f to Color.rgb(220, 38, 38)).forEach { (temp, color) ->
            if (temp in low..high) {
                paint.color = color
                paint.strokeWidth = 1.5f * density
                canvas.drawLine(left, y(temp), right, y(temp), paint)
            }
        }
        val endTime = System.currentTimeMillis()
        val startTime = endTime - windowHours * 60L * 60_000L
        for (step in 0..3) {
            val x = left + (right - left) * step / 3f
            val time = startTime + (endTime - startTime) * step / 3
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(71, 85, 105)
            val label = timeFormat.format(Date(time))
            canvas.drawText(label, x - paint.measureText(label) / 2, height - 10f * density, paint)
        }
        if (samples.isEmpty()) {
            paint.textSize = 14f * density
            paint.color = Color.rgb(100, 116, 139)
            val message = "No temperature samples yet"
            canvas.drawText(message, (width - paint.measureText(message)) / 2, (top + bottom) / 2, paint)
            return
        }
        line.reset()
        samples.forEachIndexed { index, sample ->
            val x = left + ((sample.recordedAt - startTime).toFloat() / (endTime - startTime).toFloat())
                .coerceIn(0f, 1f) * (right - left)
            val pointY = y(sample.temperatureTenthsC / 10f)
            if (index == 0) line.moveTo(x, pointY) else line.lineTo(x, pointY)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.color = Color.rgb(77, 124, 15)
        canvas.drawPath(line, paint)

        selectedIndex?.let { index ->
            val sample = samples.getOrNull(index) ?: return@let
            val selectedX = left + ((sample.recordedAt - startTime).toFloat() /
                (endTime - startTime).toFloat()).coerceIn(0f, 1f) * (right - left)
            val selectedY = y(sample.temperatureTenthsC / 10f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = Color.rgb(51, 65, 85)
            canvas.drawLine(selectedX, top, selectedX, bottom, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(77, 124, 15)
            canvas.drawCircle(selectedX, selectedY, 5f * density, paint)

            val title = String.format(
                Locale.getDefault(),
                "%s   %.1f°C",
                selectedTimeFormat.format(Date(sample.recordedAt)),
                sample.temperatureTenthsC / 10.0
            )
            val detail = "Battery ${sample.batteryLevel}%   ${if (sample.isCharging) "Charging" else "On battery"}"
            paint.textSize = 12f * density
            val boxWidth = min(
                max(paint.measureText(title), paint.measureText(detail)) + 20f * density,
                right - left
            )
            val boxHeight = 48f * density
            val boxLeft = (selectedX - boxWidth / 2).coerceIn(left, right - boxWidth)
            paint.color = Color.rgb(241, 245, 249)
            canvas.drawRoundRect(boxLeft, top, boxLeft + boxWidth, top + boxHeight, 6f * density, 6f * density, paint)
            paint.color = Color.rgb(15, 23, 42)
            canvas.drawText(title, boxLeft + 10f * density, top + 19f * density, paint)
            paint.textSize = 10f * density
            paint.color = Color.rgb(71, 85, 105)
            canvas.drawText(detail, boxLeft + 10f * density, top + 38f * density, paint)
        }
    }
}
