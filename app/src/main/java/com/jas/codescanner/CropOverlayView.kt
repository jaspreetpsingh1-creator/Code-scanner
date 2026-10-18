package com.jas.codescanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the adjustable "code box" over the camera preview, dims everything outside it,
 * and outlines detected text: green = being read, grey = ignored.
 * Touches that don't hit the box fall through to the camera (tap-to-focus).
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Box position as fractions of the view, so it survives rotation/resizing.
    private var fl = 0.04f
    private var ft = 0.08f
    private var fr = 0.96f
    private var fb = 0.56f

    private val crop = RectF()
    @Volatile private var cropSnapshot = RectF()
    @Volatile private var usedBoxes: List<RectF> = emptyList()
    @Volatile private var ignoredBoxes: List<RectF> = emptyList()

    var onCropChanged: ((Float, Float, Float, Float) -> Unit)? = null

    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(5f); strokeCap = Paint.Cap.ROUND
    }
    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(61, 220, 132); strokeWidth = dp(1.5f)
    }
    private val ignoredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(150, 180, 180, 180); strokeWidth = dp(1f)
    }

    fun setCropFractions(l: Float, t: Float, r: Float, b: Float) {
        if (r - l < 0.1f || b - t < 0.05f) return
        fl = l; ft = t; fr = r; fb = b
        updateCrop()
        invalidate()
    }

    /** Thread-safe copy of the box, in view coordinates. */
    fun cropRectSnapshot(): RectF = RectF(cropSnapshot)

    fun setBoxes(used: List<RectF>, ignored: List<RectF>) {
        usedBoxes = used
        ignoredBoxes = ignored
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCrop()
    }

    private fun updateCrop() {
        crop.set(fl * width, ft * height, fr * width, fb * height)
        cropSnapshot = RectF(crop)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, crop.top, dimPaint)
        canvas.drawRect(0f, crop.bottom, w, h, dimPaint)
        canvas.drawRect(0f, crop.top, crop.left, crop.bottom, dimPaint)
        canvas.drawRect(crop.right, crop.top, w, crop.bottom, dimPaint)

        for (r in ignoredBoxes) canvas.drawRect(r, ignoredPaint)
        for (r in usedBoxes) canvas.drawRect(r, usedPaint)

        canvas.drawRect(crop, borderPaint)
        val c = dp(22f)
        val l = crop.left; val t = crop.top; val r = crop.right; val b = crop.bottom
        canvas.drawLine(l, t, l + c, t, handlePaint); canvas.drawLine(l, t, l, t + c, handlePaint)
        canvas.drawLine(r, t, r - c, t, handlePaint); canvas.drawLine(r, t, r, t + c, handlePaint)
        canvas.drawLine(l, b, l + c, b, handlePaint); canvas.drawLine(l, b, l, b - c, handlePaint)
        canvas.drawLine(r, b, r - c, b, handlePaint); canvas.drawLine(r, b, r, b - c, handlePaint)
    }

    // --- dragging -------------------------------------------------------------

    private var dragL = false
    private var dragT = false
    private var dragR = false
    private var dragB = false
    private var dragMove = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slop = dp(32f)
                val inY = y > crop.top - slop && y < crop.bottom + slop
                val inX = x > crop.left - slop && x < crop.right + slop
                dragL = inY && abs(x - crop.left) < slop
                dragR = inY && abs(x - crop.right) < slop
                dragT = inX && abs(y - crop.top) < slop
                dragB = inX && abs(y - crop.bottom) < slop
                dragMove = !(dragL || dragR || dragT || dragB) && crop.contains(x, y)
                if (!(dragL || dragR || dragT || dragB || dragMove)) return false
                lastX = x; lastY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x; lastY = y
                val minW = dp(100f)
                val minH = dp(60f)
                val w = width.toFloat()
                val h = height.toFloat()
                if (dragMove) {
                    val ox = dx.coerceIn(-crop.left, w - crop.right)
                    val oy = dy.coerceIn(-crop.top, h - crop.bottom)
                    crop.offset(ox, oy)
                } else {
                    if (dragL) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minW)
                    if (dragR) crop.right = (crop.right + dx).coerceIn(crop.left + minW, w)
                    if (dragT) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minH)
                    if (dragB) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minH, h)
                }
                cropSnapshot = RectF(crop)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (width > 0 && height > 0) {
                    fl = max(0f, crop.left / width); fr = min(1f, crop.right / width)
                    ft = max(0f, crop.top / height); fb = min(1f, crop.bottom / height)
                    onCropChanged?.invoke(fl, ft, fr, fb)
                }
                dragL = false; dragR = false; dragT = false; dragB = false; dragMove = false
                return true
            }
        }
        return true
    }
}
