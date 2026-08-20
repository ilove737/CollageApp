package com.example.collage

import android.graphics.*
import kotlin.math.*

/**
 * 滤镜类型（阶段4 完整实现 ColorMatrix 变换）。
 */
enum class ImageFilter {
    NONE, BRIGHTNESS, CONTRAST, GRAYSCALE, SEPIA, WARM, COOL, INVERT
}

/**
 * 蒙版形状（阶段4 完整实现 Path 遮罩合成）。
 */
enum class MaskShape {
    NONE, CIRCLE, HEART, STAR, BUBBLE, ROUNDED_RECT
}

object ImageEffects {

    /** 根据滤镜类型生成 ColorMatrix */
    fun filterMatrix(f: ImageFilter): ColorMatrix {
        val m = ColorMatrix()
        when (f) {
            ImageFilter.NONE -> Unit
            ImageFilter.BRIGHTNESS -> m.setScale(1.2f, 1.2f, 1.2f, 1f)
            ImageFilter.CONTRAST -> {
                val c = 1.4f; val t = (1 - c) / 2f
                m.set(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            ImageFilter.GRAYSCALE -> m.setSaturation(0f)
            ImageFilter.SEPIA -> m.set(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            ImageFilter.WARM -> m.set(floatArrayOf(
                1.2f, 0f, 0f, 0f, 0.05f,
                0f, 1.1f, 0f, 0f, 0.02f,
                0f, 0f, 0.9f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            ImageFilter.COOL -> m.set(floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 0.05f,
                0f, 0f, 0f, 1f, 0f
            ))
            ImageFilter.INVERT -> m.set(floatArrayOf(
                -1f, 0f, 0f, 0f, 1f,
                0f, -1f, 0f, 0f, 1f,
                0f, 0f, -1f, 0f, 1f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        return m
    }

    /** 应用滤镜到 Bitmap（返回新 Bitmap） */
    fun applyFilter(src: Bitmap, f: ImageFilter): Bitmap {
        if (f == ImageFilter.NONE) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(filterMatrix(f)) }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /** 构建蒙版 Path（归一化到 0..size 的矩形内） */
    fun maskPath(shape: MaskShape, w: Float, h: Float): Path {
        val p = Path()
        when (shape) {
            MaskShape.NONE -> { p.addRect(0f, 0f, w, h, Path.Direction.CW) }
            MaskShape.CIRCLE -> p.addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
            MaskShape.ROUNDED_RECT -> p.addRoundRect(RectF(0f, 0f, w, h), w * 0.12f, w * 0.12f, Path.Direction.CW)
            MaskShape.HEART -> heartPath(p, w, h)
            MaskShape.STAR -> starPath(p, w, h, 5)
            MaskShape.BUBBLE -> bubblePath(p, w, h)
        }
        return p
    }

    /** 应用蒙版：返回裁剪后的 Bitmap（透明区域被挖空） */
    fun applyMask(src: Bitmap, shape: MaskShape): Bitmap {
        if (shape == MaskShape.NONE) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = maskPath(shape, src.width.toFloat(), src.height.toFloat())
        c.clipPath(path)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun heartPath(p: Path, w: Float, h: Float) {
        val sx = w / 2f; val sy = h / 2f
        val s = min(w, h) / 2f
        p.moveTo(sx, sy + s * 0.5f)
        p.cubicTo(sx + s, sy - s * 0.6f, sx + s * 1.3f, sy + s * 0.4f, sx, sy + s)
        p.cubicTo(sx - s * 1.3f, sy + s * 0.4f, sx - s, sy - s * 0.6f, sx, sy + s * 0.5f)
        p.close()
    }

    private fun starPath(p: Path, w: Float, h: Float, points: Int) {
        val cx = w / 2f; val cy = h / 2f
        val R = min(w, h) / 2f; val r = R * 0.45f
        val step = Math.PI / points
        for (i in 0 until points * 2) {
            val rad = if (i % 2 == 0) R else r
            val a = -Math.PI / 2 + i * step
            val x = (cx + rad * cos(a)).toFloat()
            val y = (cy + rad * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    private fun bubblePath(p: Path, w: Float, h: Float) {
        val r = w * 0.18f
        p.addRoundRect(RectF(0f, 0f, w, h * 0.82f), r, r, Path.Direction.CW)
        // 小尾巴
        p.moveTo(w * 0.3f, h * 0.78f)
        p.lineTo(w * 0.2f, h)
        p.lineTo(w * 0.5f, h * 0.82f)
        p.close()
    }
}
