package com.openeeq

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Custom EQ graph view — draws frequency response curve and allows
 * drag-to-edit of parametric EQ points.
 *
 * X axis: 20 Hz – 20 kHz (log scale)
 * Y axis: -12 dB to +12 dB
 */
class EqGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val DB_RANGE  = 12f      // ±12 dB
        private const val FREQ_MIN  = 20f
        private const val FREQ_MAX  = 20000f
        private const val TOUCH_RADIUS_PX = 60f

        // Grid frequencies for vertical lines
        private val GRID_FREQS = listOf(
            20f, 50f, 100f, 200f, 500f,
            1000f, 2000f, 5000f, 10000f, 20000f
        )
        // Grid dB for horizontal lines
        private val GRID_DB = listOf(-12f, -9f, -6f, -3f, 0f, 3f, 6f, 9f, 12f)
    }

    // ── Public state ──────────────────────────────────────────────────────────

    /** Parametric bands from EqEngine — read-only view; edit via callback. */
    var bands: Array<EqEngine.ParamBand?> = arrayOfNulls(EqEngine.PARAM_BANDS)
        set(value) { field = value; invalidate() }

    /** Called when user drags a point. -1 = new point requested at (hz,db). */
    var onBandChanged: ((index: Int, hz: Float, db: Float, q: Float) -> Unit)? = null
    var onBandRemoved: ((index: Int) -> Unit)? = null

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0D1117")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2A38")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A3D50")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00B4D8")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A6380")
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    // ── Touch state ───────────────────────────────────────────────────────────

    private var dragIndex = -1   // index into bands[] being dragged
    private var longPressIndex = -1

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        drawGrid(canvas, w, h)
        drawCurve(canvas, w, h)
        drawPoints(canvas, w, h)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        // Vertical freq lines
        for (freq in GRID_FREQS) {
            val x = freqToX(freq, w)
            canvas.drawLine(x, 0f, x, h, gridPaint)
            val label = if (freq >= 1000f) "${(freq / 1000).toInt()}k" else "${freq.toInt()}"
            canvas.drawText(label, x, h - 6f, labelPaint)
        }
        // Horizontal dB lines
        for (db in GRID_DB) {
            val y = dbToY(db, h)
            val paint = if (db == 0f) zeroPaint else gridPaint
            canvas.drawLine(0f, y, w, y, paint)
            if (db != -12f && db != 12f) {
                canvas.drawText("${db.toInt()}", 28f, y - 4f, labelPaint)
            }
        }
    }

    /**
     * Draws the combined frequency response of all active parametric bands
     * as a sum of biquad-style bell/shelf responses.
     */
    private fun drawCurve(canvas: Canvas, w: Float, h: Float) {
        val steps = w.toInt().coerceAtLeast(200)
        val path = Path()
        val fillPath = Path()

        val zeroY = dbToY(0f, h)
        fillPath.moveTo(0f, zeroY)

        for (i in 0..steps) {
            val x = i * w / steps
            val freq = xToFreq(x, w)
            val db = sumResponse(freq)
            val y = dbToY(db, h)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(w, zeroY)
        fillPath.close()

        // Gradient fill under curve
        val shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#3000B4D8"),
                Color.parseColor("#0000B4D8")
            ),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, curvePaint)
    }

    private fun drawPoints(canvas: Canvas, w: Float, h: Float) {
        for ((i, band) in bands.withIndex()) {
            band ?: continue
            val x = freqToX(band.hz, w)
            val y = dbToY(band.db, h)
            canvas.drawCircle(x, y, 18f, dotPaint)
            canvas.drawCircle(x, y, 18f, dotRingPaint)
            // Band index label
            val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 20f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawText("${i + 1}", x, y + 7f, numPaint)
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        val x = event.x; val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragIndex = findNearestBand(x, y, w, h)
                if (dragIndex == -1) {
                    // Tap on empty space -> add new band if slot available
                    val slot = bands.indexOfFirst { it == null }
                    if (slot >= 0) {
                        val hz = xToFreq(x, w).coerceIn(20f, 20000f)
                        val db = yToDb(y, h).coerceIn(-12f, 12f)
                        onBandChanged?.invoke(slot, hz, db, 1.0f)
                        dragIndex = slot
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex >= 0) {
                    val hz = xToFreq(x, w).coerceIn(20f, 20000f)
                    val db = yToDb(y, h).coerceIn(-12f, 12f)
                    val q = bands[dragIndex]?.q ?: 1.0f
                    onBandChanged?.invoke(dragIndex, hz, db, q)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestBand(x: Float, y: Float, w: Float, h: Float): Int {
        var best = -1
        var bestDist = TOUCH_RADIUS_PX
        for ((i, band) in bands.withIndex()) {
            band ?: continue
            val bx = freqToX(band.hz, w)
            val by = dbToY(band.db, h)
            val dist = hypot(x - bx, y - by)
            if (dist < bestDist) { bestDist = dist; best = i }
        }
        return best
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    private fun freqToX(hz: Float, w: Float): Float {
        val logMin = log10(FREQ_MIN)
        val logMax = log10(FREQ_MAX)
        return ((log10(hz) - logMin) / (logMax - logMin) * w).toFloat()
    }

    private fun xToFreq(x: Float, w: Float): Float {
        val logMin = log10(FREQ_MIN)
        val logMax = log10(FREQ_MAX)
        return 10f.pow((x / w * (logMax - logMin) + logMin).toFloat())
    }

    private fun dbToY(db: Float, h: Float): Float =
        h / 2f - (db / DB_RANGE) * (h / 2f)

    private fun yToDb(y: Float, h: Float): Float =
        -(y - h / 2f) / (h / 2f) * DB_RANGE

    /**
     * Parametric bell filter gain at [freq] Hz for a single band.
     * Uses the textbook peaking EQ formula: H(ω) = 1 + A*bandwidth_gain
     * Simplified to a Gaussian approximation for display only
     * (the actual audio math is done by DynamicsProcessing).
     */
    private fun bellGain(freq: Float, band: EqEngine.ParamBand): Float {
        val logRatio = log10(freq / band.hz)
        val sigma = 1f / (band.q * 2f)
        return band.db * exp(-(logRatio * logRatio) / (2f * sigma * sigma)).toFloat()
    }

    private fun sumResponse(freq: Float): Float {
        var total = 0f
        for (band in bands) {
            band ?: continue
            total += bellGain(freq, band)
        }
        return total.coerceIn(-DB_RANGE, DB_RANGE)
    }
}
