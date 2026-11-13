package org.caniorebirth.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Draws the 96 cell voltages as a coloured grid (8 columns x 12 rows).
 * Colour scale: red (low) -> yellow -> green (high). Null cells are grey.
 */
enum class CellGridMode { VOLTAGE, TEMPERATURE }

class CellGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mode: CellGridMode = CellGridMode.VOLTAGE
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private var voltages: List<Double?> = emptyList()
    private var temperatures: List<Double?> = emptyList()
    var onCellClick: ((index: Int, voltage: Double?, temperature: Double?) -> Unit)? = null

    fun setVoltages(v: List<Double?>) {
        voltages = v
        invalidate()
    }

    fun setTemperatures(t: List<Double?>) {
        temperatures = t
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val cols = 8
            val rows = 12
            val cellW = width / cols.toFloat()
            val cellH = height / rows.toFloat()
            val col = (event.x / cellW).toInt().coerceIn(0, cols - 1)
            val row = (event.y / cellH).toInt().coerceIn(0, rows - 1)
            val idx = row * cols + col
            if (idx < voltages.size) {
                onCellClick?.invoke(idx, voltages.getOrElse(idx) { null }, temperatures.getOrElse(idx) { null })
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cols = 8
        val rows = 12
        val count = cols * rows
        val cellW = width / cols.toFloat()
        val cellH = height / rows.toFloat()
        val pad = 2f
        for (i in 0 until count) {
            val col = i % cols
            val row = i / cols
            val x = col * cellW + pad
            val y = row * cellH + pad
            val w = cellW - 2 * pad
            val h = cellH - 2 * pad
            val v = voltages.getOrElse(i) { null }
            paint.color = colorFor(v)
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), 6f, 6f, paint)
            if (v != null) {
                val label = when (mode) {
                    CellGridMode.VOLTAGE -> "%.2f".format(v)
                    CellGridMode.TEMPERATURE -> "%.0f".format(v)
                }
                canvas.drawText(label, x + w / 2f, y + h / 2f + 8f, textPaint)
            }
        }
    }

    private fun colorFor(v: Double?): Int {
        if (v == null) return Color.parseColor("#424242")
        return when (mode) {
            CellGridMode.VOLTAGE -> {
                if (v <= 0.0) return Color.parseColor("#424242")
                val t = ((v - 3.0) / (4.2 - 3.0)).coerceIn(0.0, 1.0)
                val r = (255 * (1 - t)).toInt()
                val g = (200 * t + 55).toInt().coerceAtMost(255)
                Color.rgb(r, g, 40)
            }
            CellGridMode.TEMPERATURE -> {
                // blue (cold) -> green -> red (hot), range -20..60 C
                val t = ((v + 20.0) / 80.0).coerceIn(0.0, 1.0)
                val r = (255 * t).toInt()
                val g = (200 * (1 - kotlin.math.abs(t - 0.5) * 2) + 55).toInt().coerceAtMost(255)
                val b = (255 * (1 - t)).toInt()
                Color.rgb(r, g, b)
            }
        }
    }
}
