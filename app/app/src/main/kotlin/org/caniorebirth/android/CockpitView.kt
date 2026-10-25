package org.caniorebirth.android

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CockpitView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var speedKmh: Float = 0f
    var soc: Float = 0f
    var powerKw: Float = 0f
    var gear: String = "P"
    var ready: Boolean = false
    var charging: Boolean = false
    var handbrake: Boolean = false
    var turtle: Boolean = false
    var lights: Boolean = false
    var blinkLeft: Boolean = false
    var blinkRight: Boolean = false
    var rangeKm: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.55f

        // Background
        canvas.drawColor(Color.parseColor("#0a0f1a"))

        // SOC arc (top)
        arcPaint.color = Color.parseColor("#333333")
        arcPaint.strokeWidth = 24f
        val socRect = RectF(cx - w * 0.35f, h * 0.05f, cx + w * 0.35f, h * 0.35f)
        canvas.drawArc(socRect, 180f, 180f, false, arcPaint)
        arcPaint.color = Color.parseColor("#4caf50")
        canvas.drawArc(socRect, 180f, 180f * (soc / 100f), false, arcPaint)
        textPaint.textSize = 28f
        canvas.drawText("SOC ${soc.toInt()}%", cx, h * 0.22f, textPaint)

        // Speed (center big)
        textPaint.textSize = w * 0.18f
        textPaint.color = Color.WHITE
        canvas.drawText("${speedKmh.toInt()}", cx, cy + w * 0.06f, textPaint)
        textPaint.textSize = 24f
        canvas.drawText("km/h", cx, cy + w * 0.12f, textPaint)

        // Gear
        textPaint.textSize = 48f
        textPaint.color = Color.parseColor("#ffeb3b")
        canvas.drawText(gear, cx, cy - w * 0.18f, textPaint)

        // Power arc (bottom)
        arcPaint.color = Color.parseColor("#333333")
        arcPaint.strokeWidth = 16f
        val pRect = RectF(cx - w * 0.4f, h * 0.7f, cx + w * 0.4f, h * 0.95f)
        canvas.drawArc(pRect, 180f, 180f, false, arcPaint)
        val frac = (powerKw / 50f).coerceIn(-1f, 1f)
        arcPaint.color = if (frac >= 0) Color.parseColor("#f44336") else Color.parseColor("#2196f3")
        canvas.drawArc(pRect, 180f, 180f * frac, false, arcPaint)
        textPaint.textSize = 22f
        textPaint.color = Color.WHITE
        canvas.drawText("${powerKw.toInt()} kW", cx, h * 0.88f, textPaint)

        // Range
        textPaint.textSize = 20f
        canvas.drawText("Range ${rangeKm.toInt()} km", cx, h * 0.98f, textPaint)

        // Indicators row
        val indY = h * 0.02f
        val indicators = listOf(
            "READY" to ready,
            "CHG" to charging,
            "HB" to handbrake,
            "TURTLE" to turtle,
            "LIGHTS" to lights,
            "L" to blinkLeft,
            "R" to blinkRight
        )
        val spacing = w / (indicators.size + 1)
        textPaint.textSize = 16f
        indicators.forEachIndexed { i, (label, on) ->
            val x = spacing * (i + 1)
            paint.color = if (on) Color.parseColor("#4caf50") else Color.parseColor("#444444")
            canvas.drawCircle(x, indY + 12f, 10f, paint)
            textPaint.color = if (on) Color.WHITE else Color.GRAY
            canvas.drawText(label, x, indY + 40f, textPaint)
        }
    }
}
