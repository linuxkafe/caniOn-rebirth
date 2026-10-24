package org.caniorebirth.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the 96 cell voltages as a coloured grid (8 columns x 12 rows).
 * Colour scale: red (low) -> yellow -> green (high). Null cells are grey.
 */
class CellGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private var voltages: List<Double?> = emptyList()

    fun setVoltages(v: List<Double?>) {
        voltages = v
        invalidate()
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
                val label = "%.2f".format(v)
                canvas.drawText(label, x + w / 2f, y + h / 2f + 8f, textPaint)
            }
        }
    }

    private fun colorFor(v: Double?): Int {
        if (v == null || v <= 0.0) return Color.parseColor("#424242")
        // typical cell range 3.0 .. 4.2 V
        val t = ((v - 3.0) / (4.2 - 3.0)).coerceIn(0.0, 1.0)
        // red (low) -> yellow -> green (high)
        val r = (255 * (1 - t)).toInt()
        val g = (200 * t + 55).toInt().coerceAtMost(255)
        val b = 40
        return Color.rgb(r, g, b)
    }
}
