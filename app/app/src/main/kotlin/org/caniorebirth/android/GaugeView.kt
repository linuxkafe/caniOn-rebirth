package org.caniorebirth.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * A simple radial gauge: arc with coloured zones + needle + value text.
 * min/max define the range; value is clamped. label/unit are displayed.
 */
class GaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var min: Float = 0f
    var max: Float = 100f
    var value: Float = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }
    var label: String = ""
    var unit: String = ""

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 18f
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f + 20f
        val radius = (minOf(width, height) / 2f) - 30f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // Start angle: 135 degrees (bottom-left), sweep 270 degrees
        val startAngle = 135f
        val sweep = 270f

        // Background arc
        canvas.drawArc(rect, startAngle, sweep, false, bgPaint)

        // Coloured zones: green (0-60%), yellow (60-85%), red (85-100%)
        val range = max - min
        if (range > 0f) {
            val frac = (value - min) / range
            // Green zone
            zonePaint.color = Color.parseColor("#4CAF50")
            canvas.drawArc(rect, startAngle, sweep * 0.6f, false, zonePaint)
            // Yellow zone
            zonePaint.color = Color.parseColor("#FFC107")
            canvas.drawArc(rect, startAngle + sweep * 0.6f, sweep * 0.25f, false, zonePaint)
            // Red zone
            zonePaint.color = Color.parseColor("#F44336")
            canvas.drawArc(rect, startAngle + sweep * 0.85f, sweep * 0.15f, false, zonePaint)
        }

        // Needle
        val frac = if (range > 0f) ((value - min) / range).coerceIn(0f, 1f) else 0f
        val angleDeg = startAngle + sweep * frac
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val needleLen = radius - 10f
        val nx = cx + (needleLen * cos(angleRad)).toFloat()
        val ny = cy + (needleLen * sin(angleRad)).toFloat()
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        // Pivot
        val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, 8f, pivotPaint)

        // Value text
        canvas.drawText("%.1f".format(value), cx, cy - radius / 3f, textPaint)
        canvas.drawText(unit, cx, cy - radius / 3f + 30f, labelPaint)
        canvas.drawText(label, cx, cy + radius + 20f, labelPaint)
    }
}
