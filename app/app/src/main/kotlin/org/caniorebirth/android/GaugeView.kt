package org.caniorebirth.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

class GaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var min: Float = 0f
    var max: Float = 100f
    var label: String = ""
    var unit: String = ""

    private var displayValue: Float = 0f
    private var targetValue: Float = 0f

    var value: Float = 0f
        set(v) {
            targetValue = v.coerceIn(min, max)
            ValueAnimator.ofFloat(displayValue, targetValue).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    displayValue = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.STROKE
        strokeWidth = 20f
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f + 10f
        val radius = (minOf(width, height) / 2f) - 36f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val startAngle = 135f
        val sweep = 270f

        // Background arc
        canvas.drawArc(rect, startAngle, sweep, false, bgPaint)

        // Gradient arc for filled portion
        val range = max - min
        if (range > 0f) {
            val frac = ((displayValue - min) / range).coerceIn(0f, 1f)
            val sweepFilled = sweep * frac
            // Gradient from green to red
            val shader = LinearGradient(
                cx - radius, cy, cx + radius, cy,
                intArrayOf(Color.parseColor("#4CAF50"), Color.parseColor("#FFC107"), Color.parseColor("#F44336")),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            arcPaint.shader = shader
            canvas.drawArc(rect, startAngle, sweepFilled, false, arcPaint)
            arcPaint.shader = null
        }

        // Needle
        val frac = if (range > 0f) ((displayValue - min) / range).coerceIn(0f, 1f) else 0f
        val angleDeg = startAngle + sweep * frac
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val needleLen = radius - 8f
        val nx = cx + (needleLen * cos(angleRad)).toFloat()
        val ny = cy + (needleLen * sin(angleRad)).toFloat()
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        // Pivot circle
        val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, 10f, pivotPaint)
        val innerPivot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, 5f, innerPivot)

        // Value text
        canvas.drawText("%.1f".format(displayValue), cx, cy - radius / 4f, textPaint)
        canvas.drawText(unit, cx, cy - radius / 4f + 28f, unitPaint)
        canvas.drawText(label, cx, cy + radius + 24f, labelPaint)
    }
}
