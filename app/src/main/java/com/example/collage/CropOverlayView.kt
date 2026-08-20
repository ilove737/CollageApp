package com.example.collage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * 裁剪预览 + 九宫格拖拽框。
 * 用户拖动四个角/边调整裁剪矩形，确认后回调裁剪结果 Bitmap。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    var srcBitmap: Bitmap? = null
        set(v) { field = v; v?.let { setImageBitmap(it); resetRect() } }

    private val rect = RectF()
    private val paint = Paint().apply { color = Color.parseColor("#3F51B5"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val dim = Paint().apply { color = Color.parseColor("#80000000") }
    private var handle = ""
    private val H = 30f

    private fun resetRect() {
        val b = srcBitmap ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val scale = min(w / b.width, h / b.height)
        val dw = b.width * scale; val dh = b.height * scale
        rect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (rect.isEmpty) resetRect()
    }

    fun cropRect(): RectF = rect

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> handle = hitHandle(ev.x, ev.y)
            MotionEvent.ACTION_MOVE -> {
                if (handle.isEmpty()) return true
                when (handle) {
                    "l" -> rect.left = ev.x.coerceIn(0f, rect.right - 40f)
                    "r" -> rect.right = ev.x.coerceIn(rect.left + 40f, width.toFloat())
                    "t" -> rect.top = ev.y.coerceIn(0f, rect.bottom - 40f)
                    "b" -> rect.bottom = ev.y.coerceIn(rect.top + 40f, height.toFloat())
                    "tl" -> { rect.left = ev.x.coerceIn(0f, rect.right - 40f); rect.top = ev.y.coerceIn(0f, rect.bottom - 40f) }
                    "tr" -> { rect.right = ev.x.coerceIn(rect.left + 40f, width.toFloat()); rect.top = ev.y.coerceIn(0f, rect.bottom - 40f) }
                    "bl" -> { rect.left = ev.x.coerceIn(0f, rect.right - 40f); rect.bottom = ev.y.coerceIn(rect.top + 40f, height.toFloat()) }
                    "br" -> { rect.right = ev.x.coerceIn(rect.left + 40f, width.toFloat()); rect.bottom = ev.y.coerceIn(rect.top + 40f, height.toFloat()) }
                }
                invalidate()
            }
        }
        return true
    }

    private fun hitHandle(x: Float, y: Float): String {
        fun near(a: Float, b: Float) = abs(a - b) < H
        return when {
            near(x, rect.left) && near(y, rect.top) -> "tl"
            near(x, rect.right) && near(y, rect.top) -> "tr"
            near(x, rect.left) && near(y, rect.bottom) -> "bl"
            near(x, rect.right) && near(y, rect.bottom) -> "br"
            near(x, rect.left) -> "l"
            near(x, rect.right) -> "r"
            near(y, rect.top) -> "t"
            near(y, rect.bottom) -> "b"
            else -> "move"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) return
        // 暗化裁剪区外
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dim)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dim)
        // 边框
        canvas.drawRect(rect, paint)
        // 九宫格
        val dx = rect.width() / 3f; val dy = rect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(rect.left + dx * i, rect.top, rect.left + dx * i, rect.bottom, paint.apply { alpha = 120 })
            canvas.drawLine(rect.left, rect.top + dy * i, rect.right, rect.top + dy * i, paint.apply { alpha = 120 })
        }
        paint.alpha = 255
        // 控制点
        listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom).forEach {
            canvas.drawCircle(it.first, it.second, 8f, paint)
        }
    }

    /** 根据裁剪框从原图裁出 Bitmap */
    fun resultBitmap(): Bitmap? {
        val b = srcBitmap ?: return null
        val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val x = ((rect.left - (width - b.width * scale) / 2f) / scale).toInt().coerceAtLeast(0)
        val y = ((rect.top - (height - b.height * scale) / 2f) / scale).toInt().coerceAtLeast(0)
        val w = (rect.width() / scale).toInt().coerceAtMost(b.width - x)
        val h = (rect.height() / scale).toInt().coerceAtMost(b.height - y)
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(b, x, y, w, h)
    }
}
