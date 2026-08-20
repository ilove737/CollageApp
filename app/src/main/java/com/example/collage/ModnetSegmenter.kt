package com.example.collage

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * MODNet 人像抠图（标准 TFLite 算子，普通 Interpreter 即可运行）。
 *
 * 模型：assets/modnet.tflite（需自行放置，请见 README/方案说明）。
 * 支持的输入布局（自动探测）：
 *   - NHWC：[1, H, W, 3] float32（像素 RGB，归一化 0..1）
 *   - NCHW：[1, 3, H, W] float32（按通道排列，归一化 0..1）
 * 输出：单通道 alpha，[1, 1, H, W] 或 [1, H, W, 1]，值 0..1。
 *
 * 归一化默认用 像素/255（[0,1]）；若模型导出为 [-1,1]（mean/std=127.5），
 * 将 [NORMALIZE_TO_NEG_ONE] 置为 true。
 */
object ModnetSegmenter {

    const val MODEL_NAME = "modnet.tflite"
    /** 若模型要求输入归一化到 [-1,1]，改为 true；默认 [0,1] */
    private const val NORMALIZE_TO_NEG_ONE = false

    /**
     * alpha 软阈值重映射，用于规避袖子等边缘区域被误裁掉。
     *
     * 模型的 alpha 在人物边缘（尤其窄长的袖子、发丝）常为 0.3~0.7 的中间值，
     * 直接线性映射会把这类区域切成半透明甚至消失。这里做一次软阈值：
     *   - alpha < ALPHA_KEEP                → 0（纯背景）
     *   - alpha > ALPHA_KEEP + ALPHA_SOFT  → 原值（完整保留）
     *   - 中间值按线性插值，从 0 平滑过渡到原值，避免生硬裁切
     *
     * 想要保住更多袖子，调低 [ALPHA_KEEP]、调大 [ALPHA_SOFT]；
     * 想要更干净地去掉背景残留，则反之。
     */
    private const val ALPHA_KEEP = 0.35f
    private const val ALPHA_SOFT = 0.30f

    /** 是否对 alpha 蒙版做一次形态学膨胀，补回被吃掉的边缘（默认开）。 */
    private const val DILATE_ALPHA = true
    /** 膨胀核半径（像素，按 alpha 输出分辨率计）。半径 0 表示不膨胀。 */
    private const val DILATE_RADIUS = 2

    var lastError: String? = null
        private set

    private var interpreter: Interpreter? = null

    private fun getInterpreter(context: Context): Interpreter? {
        interpreter?.let { return it }
        return try {
            val afd = context.assets.openFd(MODEL_NAME)
            val fis = FileInputStream(afd.fileDescriptor)
            val fc = fis.channel
            var len = afd.declaredLength
            if (len <= 0) len = afd.length
            val bb = fc.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, len)

            // 使用 CPU 推理。MODNet 的 relu6/upsample 等算子在 GPU delegate
            // 上可能触发 native 崩溃导致闪退，故暂不启用 GPU 加速。
            interpreter = Interpreter(bb)
            Log.d("Modnet", "model loaded")
            interpreter
        } catch (e: Exception) {
            lastError = e.stackTraceToString()
            Log.e("Modnet", "load failed", e)
            null
        }
    }

    /** 对 [src] 抠图，返回透明背景 bitmap；失败返回 null（原因见 [lastError]）。 */
    fun segment(context: Context, src: Bitmap): Bitmap? {
        lastError = null
        val interp = getInterpreter(context) ?: return null
        return try {
            val inShape = interp.getInputTensor(0).shape()
            val outShape = interp.getOutputTensor(0).shape()

            // ---- 输入布局探测 ----
            val nhwc = inShape.size == 4 && inShape[3] == 3
            val inH = if (nhwc) inShape[1] else inShape[2]
            val inW = if (nhwc) inShape[2] else inShape[3]
            val n = inW * inH

            // 归一化 0..1 或 -1..1
            fun norm(v: Float) = if (NORMALIZE_TO_NEG_ONE) (v / 127.5f) - 1f else v / 255f

            val resized = Bitmap.createScaledBitmap(src, inW, inH, true)
            val pixels = IntArray(n)
            resized.getPixels(pixels, 0, inW, 0, 0, inW, inH)

            val rArr = FloatArray(n)
            val gArr = FloatArray(n)
            val bArr = FloatArray(n)
            var i = 0
            for (p in pixels) {
                rArr[i] = norm(((p shr 16) and 0xFF).toFloat())
                gArr[i] = norm(((p shr 8) and 0xFF).toFloat())
                bArr[i] = norm((p and 0xFF).toFloat())
                i++
            }

            // 组装输入 buffer（NHWC 或 NCHW）
            val inFloats = FloatArray(n * 3)
            if (nhwc) {
                var k = 0
                for (j in 0 until n) {
                    inFloats[k++] = rArr[j]; inFloats[k++] = gArr[j]; inFloats[k++] = bArr[j]
                }
            } else {
                // NCHW：R 通道整块，再 G，再 B
                System.arraycopy(rArr, 0, inFloats, 0, n)
                System.arraycopy(gArr, 0, inFloats, n, n)
                System.arraycopy(bArr, 0, inFloats, n * 2, n)
            }
            val inBuf = ByteBuffer.allocateDirect(n * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().put(inFloats)
            }

            // 输出 alpha：MODNet 输出为单通道方形 matte。
            // 用 numElements 确定尺寸，避免 NCHW/NHWC 布局误判。
            val outTensor = interp.getOutputTensor(0)
            val numOut = outTensor.numElements()
            val side = kotlin.math.sqrt(numOut.toFloat()).toInt()
            val outBuf = ByteBuffer.allocateDirect(numOut * 4).apply { order(ByteOrder.nativeOrder()) }
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            val alpha = FloatArray(numOut)
            outBuf.asFloatBuffer().get(alpha)

            applyAlpha(src, alpha, side, side)
        } catch (e: Exception) {
            lastError = e.stackTraceToString()
            Log.e("Modnet", "segment failed", e)
            null
        }
    }

    /**
     * 软阈值重映射 + 形态学膨胀，处理后得到最终蒙版。
     * 返回与 [alpha] 同尺寸的 float 蒙版，值 0..1。
     */
    private fun processAlpha(alpha: FloatArray, aw: Int, ah: Int): FloatArray {
        val n = alpha.size
        val remapped = FloatArray(n)
        for (i in 0 until n) {
            val a = alpha[i]
            remapped[i] = when {
                a < ALPHA_KEEP -> 0f
                a > ALPHA_KEEP + ALPHA_SOFT -> a.coerceIn(0f, 1f)
                // 中间区：从 0 线性过渡到原值（KEEP+SOFT 处回到原值）
                else -> a * ((a - ALPHA_KEEP) / ALPHA_SOFT).coerceIn(0f, 1f)
            }
        }

        if (!DILATE_ALPHA || DILATE_RADIUS <= 0 || aw <= 0 || ah <= 0) {
            return remapped
        }

        // 形态学膨胀：取半径邻域内的最大值，补回被吃掉的边缘 1~2 像素。
        val r = DILATE_RADIUS
        val dilated = FloatArray(n)
        for (y in 0 until ah) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r).coerceAtMost(ah - 1)
            for (x in 0 until aw) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(aw - 1)
                var m = remapped[y * aw + x]
                for (yy in y0..y1) {
                    val row = yy * aw
                    for (xx in x0..x1) {
                        val v = remapped[row + xx]
                        if (v > m) m = v
                    }
                }
                dilated[y * aw + x] = m
            }
        }
        return dilated
    }

    private fun applyAlpha(src: Bitmap, alpha: FloatArray, aw: Int, ah: Int): Bitmap {
        val processed = processAlpha(alpha, aw, ah)
        val sw = src.width
        val sh = src.height
        val result = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(sw * sh)
        var oi = 0
        for (y in 0 until sh) {
            val ay = (y * ah / sh).coerceIn(0, ah - 1)
            for (x in 0 until sw) {
                val ax = (x * aw / sw).coerceIn(0, aw - 1)
                val a = (processed[ay * aw + ax] * 255).toInt().coerceIn(0, 255)
                val sp = src.getPixel(x, y)
                outPixels[oi++] = (a shl 24) or (sp and 0x00FFFFFF)
            }
        }
        result.setPixels(outPixels, 0, sw, 0, 0, sw, sh)
        return result
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
