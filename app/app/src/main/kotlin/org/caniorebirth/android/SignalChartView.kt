package org.caniorebirth.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Minimal line chart for a single series of floats. Draws axes and a polyline.
 */
class SignalChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }
    private var data: List<Float> = emptyList()
    private var label: String = ""

    fun setData(series: List<Float>, seriesLabel: String = "") {
        data = series
        label = seriesLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // axes
        canvas.drawLine(40f, h - 30f, w - 10f, h - 30f, axisPaint)
        canvas.drawLine(40f, 10f, 40f, h - 30f, axisPaint)

        if (data.size < 2) {
            canvas.drawText("no data", 50f, h / 2f, textPaint)
            return
        }

        val min = data.minOrNull() ?: 0f
        val max = data.maxOrNull() ?: 1f
        val range = (max - min).takeIf { it != 0f } ?: 1f

        val usableW = w - 50f
        val usableH = h - 50f
        val stepX = usableW / (data.size - 1)

        var prevX = 40f
        var prevY = h - 30f - (data[0] - min) / range * usableH
        for (i in 1 until data.size) {
            val x = 40f + i * stepX
            val y = h - 30f - (data[i] - min) / range * usableH
            canvas.drawLine(prevX, prevY, x, y, linePaint)
            prevX = x
            prevY = y
        }
        canvas.drawText("$label  min ${"%.2f".format(min)} max ${"%.2f".format(max)}", 50f, 30f, textPaint)
    }
}
