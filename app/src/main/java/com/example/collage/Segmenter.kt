package com.example.collage

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer

/**
 * 人像分割（抠图）：使用 MediaPipe ImageSegmenter 对图片推理得到人像 alpha 蒙版，
 * 将蒙版合成到原图得到透明背景。
 *
 * 模型：assets/selfie_segmenter.tflite（MediaPipe 轻量人像分割，含自定义算子，
 * 必须用 MediaPipe ImageSegmenter 运行，普通 TFLite Interpreter 无法加载）。
 */
object Segmenter {

    private const val MODEL_NAME = "selfie_segmenter.tflite"

    /**
     * 是否优先使用 MODNet 模型抠图。
     * true 且 assets/modnet.tflite 存在时走 MODNet（标准 TFLite 推理）；
     * 否则回退到 MediaPipe selfie_segmenter。
     */
    var useModnet: Boolean = true
        private set

    var lastError: String? = null
        private set

    private var segmenter: ImageSegmenter? = null

    private fun getSegmenter(context: Context): ImageSegmenter? {
        segmenter?.let { return it }
        return try {
            val base = BaseOptions.builder().setModelAssetPath(MODEL_NAME).build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .build()
            segmenter = ImageSegmenter.createFromOptions(context, options)
            segmenter
        } catch (e: Exception) {
            lastError = e.stackTraceToString()
            Log.e("Segmenter", "createFromOptions failed", e)
            null
        }
    }

    /**
     * 对 [src] 抠图，返回带透明通道的新 bitmap；失败返回 null（原因见 [lastError]）。
     */
    fun segment(context: Context, src: Bitmap): Bitmap? {
        lastError = null
        // 优先使用 MODNet（若已启用且模型存在）
        if (useModnet) {
            val modnetModelExists = try {
                context.assets.openFd(ModnetSegmenter.MODEL_NAME).use { true }
            } catch (e: Exception) { false }
            if (modnetModelExists) {
                return ModnetSegmenter.segment(context, src)
            }
            // 模型不存在：记录原因并回退 MediaPipe
            lastError = "modnet.tflite not found, fallback to MediaPipe"
            Log.w("Segmenter", lastError ?: "")
        }
        val seg = getSegmenter(context) ?: return null
        return try {
            val mpImage = BitmapImageBuilder(src).build()
            val result = seg.segment(mpImage)
            val masks = result.confidenceMasks()
            if (!masks.isPresent || masks.get().isEmpty()) {
                lastError = "no confidence mask output"
                return null
            }
            applyMask(src, masks.get()[0])
        } catch (e: Exception) {
            lastError = e.stackTraceToString()
            Log.e("Segmenter", "segment failed", e)
            null
        }
    }

    /** 将单通道 alpha 蒙版（MPImage，float 0..1）合成为透明背景结果 */
    private fun applyMask(src: Bitmap, mask: MPImage): Bitmap {
        val mw = mask.width
        val mh = mask.height
        val buf: ByteBuffer = ByteBufferExtractor.extract(mask)
        buf.rewind()
        val floats = FloatArray(mw * mh)
        buf.asFloatBuffer().get(floats)

        val sw = src.width
        val sh = src.height
        val result = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(sw * sh)
        var oi = 0
        for (y in 0 until sh) {
            val my = (y * mh / sh).coerceIn(0, mh - 1)
            for (x in 0 until sw) {
                val mx = (x * mw / sw).coerceIn(0, mw - 1)
                val a = (floats[my * mw + mx] * 255).toInt().coerceIn(0, 255)
                val sp = src.getPixel(x, y)
                outPixels[oi++] = (a shl 24) or (sp and 0x00FFFFFF)
            }
        }
        result.setPixels(outPixels, 0, sw, 0, 0, sw, sh)
        return result
    }

    fun release() {
        segmenter?.close()
        segmenter = null
    }
}
