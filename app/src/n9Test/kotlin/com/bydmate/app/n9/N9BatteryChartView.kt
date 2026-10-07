package com.bydmate.app.n9

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

internal data class N9ChartPoint(val timestamp: Long, val value: Double)

/** Keep acquisition order: a missing reading, clock rollback or long gap breaks a line. */
internal fun n9ChartSegments(samples: List<N9Sample>, key: String, maxGapMs: Long = 90_000): List<List<N9ChartPoint>> {
    val result = mutableListOf<List<N9ChartPoint>>()
    var segment = mutableListOf<N9ChartPoint>()
    fun flush() {
        if (segment.isNotEmpty()) result += segment.toList()
        segment = mutableListOf()
    }
    samples.forEach { sample ->
        val value = sample.values[key]?.takeIf { it.isFinite() }
        val last = segment.lastOrNull()
        if (value == null) {
            flush()
        } else {
            if (last != null && (sample.timestamp <= last.timestamp || sample.timestamp - last.timestamp > maxGapMs)) flush()
            segment += N9ChartPoint(sample.timestamp, value)
        }
    }
    flush()
    return result
}

/** Timestamp-scaled read-only plot; no interpolation across missing samples. */
internal class N9BatteryChartView(context: Context, samples: List<N9Sample>, private val field: N9BatteryField,
    private val independentPoints: Boolean = false) : View(context) {
    private val segments = if (independentPoints) samples.mapNotNull { sample ->
        sample.values[field.key]?.takeIf { it.isFinite() }?.let { listOf(N9ChartPoint(sample.timestamp, it)) }
    } else n9ChartSegments(samples, field.key)
    private val points = segments.flatten()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val green = Color.rgb(80, 221, 151)
    private val muted = Color.rgb(159, 180, 206)

    init { contentDescription = if (independentPoints) "${field.label}跨次充电散点；每个点代表一个观察区间" else "${field.label}时间曲线；缺失值和超过90秒的间隔断开" }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pen.textSize = 12f * resources.displayMetrics.scaledDensity
        pen.typeface = android.graphics.Typeface.DEFAULT
        pen.style = Paint.Style.FILL
        pen.color = muted
        if (points.isEmpty()) {
            canvas.drawText("暂无可绘制数据；缺失字段不会补值", 12f * density, height / 2f, pen)
            return
        }
        val left = 70f * density
        val right = max(left + 1f, width - 20f * density)
        val top = 20f * density
        val bottom = max(top + 1f, height - 34f * density)
        val firstTime = points.minOf { it.timestamp }
        val lastTime = points.maxOf { it.timestamp }
        val low = points.minOf { it.value }
        val high = points.maxOf { it.value }
        val padding = if (low == high) max(1.0, kotlin.math.abs(low) * .02) else (high - low) * .08
        val yMin = low - padding
        val yMax = high + padding
        fun x(time: Long) = if (firstTime == lastTime) (left + right) / 2f else
            left + ((time - firstTime).toDouble() / (lastTime - firstTime) * (right - left)).toFloat()
        fun y(value: Double) = bottom - ((value - yMin) / (yMax - yMin) * (bottom - top)).toFloat()
        pen.strokeWidth = density
        repeat(3) { index ->
            val value = yMin + (yMax - yMin) * index / 2.0
            val yy = y(value)
            pen.color = Color.rgb(43, 64, 88)
            canvas.drawLine(left, yy, right, yy, pen)
            pen.color = muted
            pen.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "%.${field.decimals.coerceAtMost(2)}f", value), left - 8f * density, yy + 4f * density, pen)
        }
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        pen.textAlign = Paint.Align.LEFT
        canvas.drawText(time.format(Date(firstTime)), left, height - 8f * density, pen)
        if (lastTime != firstTime && width > 400 * density) {
            pen.textAlign = Paint.Align.RIGHT
            canvas.drawText(time.format(Date(lastTime)), right, height - 8f * density, pen)
        }
        pen.textAlign = Paint.Align.LEFT
        pen.color = green
        pen.strokeWidth = 2.5f * density
        segments.forEach { segment ->
            if (segment.size == 1) {
                pen.style = Paint.Style.FILL
                canvas.drawCircle(x(segment[0].timestamp), y(segment[0].value), 3f * density, pen)
            } else {
                val path = Path()
                segment.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(x(point.timestamp), y(point.value)) else path.lineTo(x(point.timestamp), y(point.value))
                }
                pen.style = Paint.Style.STROKE
                canvas.drawPath(path, pen)
            }
        }
    }
}
