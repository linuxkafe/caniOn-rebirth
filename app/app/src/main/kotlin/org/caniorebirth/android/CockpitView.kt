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

    private val bgBitmap: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.cockpit)
    } catch (_: Exception) { null }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // Draw cockpit background scaled to fit
        bgBitmap?.let { bmp ->
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = RectF(0f, 0f, w, h)
            canvas.drawBitmap(bmp, src, dst, paint)
        } ?: canvas.drawColor(Color.parseColor("#0a0f1a"))

        // Darken slightly for readability of overlays
        paint.color = Color.argb(60, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, paint)

        // Speed (large, center-ish)
        textPaint.textSize = w * 0.12f
        textPaint.color = Color.WHITE
        canvas.drawText("${speedKmh.toInt()}", w * 0.5f, h * 0.55f, textPaint)
        textPaint.textSize = 18f
        canvas.drawText("km/h", w * 0.5f, h * 0.6f, textPaint)

        // Gear
        textPaint.textSize = 40f
        textPaint.color = Color.parseColor("#ffeb3b")
        canvas.drawText(gear, w * 0.5f, h * 0.35f, textPaint)

        // SOC (top right area)
        textPaint.textSize = 22f
        textPaint.color = Color.parseColor("#4caf50")
        canvas.drawText("SOC ${soc.toInt()}%", w * 0.82f, h * 0.25f, textPaint)

        // Power (bottom)
        textPaint.textSize = 20f
        textPaint.color = if (powerKw >= 0) Color.parseColor("#f44336") else Color.parseColor("#2196f3")
        canvas.drawText("${powerKw.toInt()} kW", w * 0.5f, h * 0.85f, textPaint)

        // Range
        textPaint.textSize = 16f
        textPaint.color = Color.WHITE
        canvas.drawText("Range ${rangeKm.toInt()} km", w * 0.5f, h * 0.92f, textPaint)

        // Indicators
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
        textPaint.textSize = 14f
        indicators.forEachIndexed { i, (label, on) ->
            val x = spacing * (i + 1)
            val y = h * 0.08f
            paint.color = if (on) Color.parseColor("#4caf50") else Color.parseColor("#555555")
            canvas.drawCircle(x, y, 8f, paint)
            textPaint.color = if (on) Color.WHITE else Color.GRAY
            canvas.drawText(label, x, y + 22f, textPaint)
        }
    }
}
