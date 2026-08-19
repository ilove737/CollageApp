package com.example.collage

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CollageApp"
        private const val MAX_SIDE = 1600  // 单图解码最大边长（防 OOM）
        private const val MAX_DIM = 4096   // 横/纵/网格结果最大边长
        private const val MAX_LONG = 8192  // 智能长图最大总高度
        private const val CELL = 600       // 网格模式格子边长
        private const val MODE_HOR = 0
        private const val MODE_VER = 1
        private const val MODE_GRID = 2
        private const val MODE_SMART = 3
        // TFLite 人像抠图（u2net_human_seg，轻量人像分割，优于 selfie_segmenter）
        private const val MODEL_TFLITE = "u2net_human_seg.tflite"
        private const val U2NET_INPUT_SIZE = 320  // u2net_human_seg 标准输入边长
        // 抠图后处理阈值（置信度连续值直接当 alpha 会发灰/有残影，故加硬切+羽化）
        private const val MASK_THRESHOLD = 0.5f  // 低于此值视为背景（全透明）
        private const val MASK_FEATHER = 0.18f   // 阈值附近羽化半宽，越大边缘越柔、越小越锐利
    }

    private val images = mutableListOf<Uri>()
    private var selectedColor = 0
    private val colors = intArrayOf(
        Color.WHITE,
        Color.BLACK,
        0xFF607D8B.toInt(),  // 蓝灰
        0xFFF44336.toInt(),  // 红
        0xFF2196F3.toInt(),  // 蓝
        0xFFFF9800.toInt()   // 橙
    )

    private lateinit var gridPreview: GridView
    private lateinit var tvCount: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var gridOption: LinearLayout
    private lateinit var spCols: Spinner
    private lateinit var seekGap: SeekBar
    private lateinit var seekRadius: SeekBar
    private lateinit var tvGap: TextView
    private lateinit var tvRadius: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var btnMerge: Button

    // 系统相册选择器：Android 13+ 走 Photo Picker（免权限），旧系统自动回退
    // 追加模式：多次选择会累计图片（去重，上限 9 张）
    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (u in uris) {
                if (!images.contains(u) && images.size < 9) {
                    images.add(u)
                }
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gridPreview = findViewById(R.id.gridPreview)
        tvCount = findViewById(R.id.tvCount)
        modeSpinner = findViewById(R.id.modeSpinner)
        gridOption = findViewById(R.id.gridOption)
        spCols = findViewById(R.id.spCols)
        seekGap = findViewById(R.id.seekGap)
        seekRadius = findViewById(R.id.seekRadius)
        tvGap = findViewById(R.id.tvGap)
        tvRadius = findViewById(R.id.tvRadius)
        colorRow = findViewById(R.id.colorRow)
        btnMerge = findViewById(R.id.btnMerge)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // 只有「网格」模式才有列数选项
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                gridOption.visibility = if (position == MODE_GRID) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        seekGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvGap.text = "${progress}px"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvRadius.text = "${progress}px"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnMerge.setOnClickListener { merge() }
        buildColorRow()
        refresh()
    }

    // ---------- 边框/背景色色块 ----------
    private fun buildColorRow() {
        colorRow.removeAllViews()
        colors.forEachIndexed { i, c ->
            val v = View(this)
            val size = dp(30)
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(8)
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (i == selectedColor) {
                    setStroke(dp(3), 0xFF37474F.toInt())
                } else {
                    setStroke(dp(1), 0x66000000)
                }
            }
            v.background = bg
            v.setOnClickListener {
                selectedColor = i
                buildColorRow()
            }
            colorRow.addView(v)
        }
    }

    private fun refresh() {
        tvCount.text = getString(R.string.count_hint, images.size)
        gridPreview.adapter = ThumbAdapter()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 缩略图适配器（末尾固定一个「+」添加格；点图弹菜单：前移/后移/删除） ----------
    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount(): Int = images.size + 1

        override fun getItem(position: Int): Any =
            if (position < images.size) images[position] else Unit

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (position == images.size) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            // 末尾「+」添加格：点它加图
            if (position == images.size) {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_add, parent, false)
                v.setOnClickListener {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                return v
            }
            val root = convertView ?: layoutInflater.inflate(R.layout.item_thumb, parent, false)
            root.findViewById<ImageView>(R.id.thumb)
                .setImageBitmap(decode(images[position], dp(150)))
            root.findViewById<View>(R.id.btnRemove).setOnClickListener {
                images.removeAt(position)
                refresh()
            }
            root.setOnClickListener { showImageMenu(position) }
            return root
        }
    }

    private fun showImageMenu(p: Int) {
        val items = arrayOf(
            getString(R.string.move_prev),
            getString(R.string.move_next),
            getString(R.string.segment_person),
            getString(R.string.del)
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (p > 0) {
                        val t = images[p]; images[p] = images[p - 1]; images[p - 1] = t; refresh()
                    } else {
                        toast(getString(R.string.already_first))
                    }
                    1 -> if (p < images.size - 1) {
                        val t = images[p]; images[p] = images[p + 1]; images[p + 1] = t; refresh()
                    } else {
                        toast(getString(R.string.already_last))
                    }
                    2 -> segmentImage(p)
                    3 -> {
                        images.removeAt(p)
                        refresh()
                    }
                }
            }
            .show()
    }

    // ---------- 人像抠图（MediaPipe Selfie Segmenter，本地推理） ----------
    // 抠图结果保存为透明 PNG（app 私有目录），替换原图参与拼接。
    private fun segmentImage(p: Int) {
        val uri = images[p]
        val btn = btnMerge
        btn.isEnabled = false
        val originText = btn.text
        btn.text = getString(R.string.segmenting)
        Thread {
            val result = try {
                val bmp = decode(uri, 1024) ?: throw IllegalStateException("图片解码失败")
                segmentPerson(bmp)
            } catch (e: Exception) {
                Log.e(TAG, "抠图异常", e)
                null
            }
            runOnUiThread {
                btn.isEnabled = true
                btn.text = originText
                if (result != null) {
                    try {
                        val dir = File(filesDir, "segment").apply { mkdirs() }
                        val png = File(dir, "seg_${System.currentTimeMillis()}.png")
                        png.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        result.recycle()
                        // 换成 FileProvider uri，后续 decode/合成照常工作
                        images[p] = FileProvider.getUriForFile(this, "$packageName.fileprovider", png)
                        refresh()
                        toast(getString(R.string.segment_ok))
                    } catch (e: Exception) {
                        Log.e(TAG, "保存抠图结果失败", e)
                        toast(getString(R.string.segment_fail))
                    }
                } else {
                    toast(getString(R.string.segment_fail))
                }
            }
        }.start()
    }

    /**
     * 人像抠图总入口：优先用 TFLite（MODNet/RMBG，质量更高），失败再回退 MediaPipe Selfie Segmenter。
     * 两条路径最终都复用 applyMask() 的「缩放 + alpha」后处理逻辑。
     */
    private fun segmentPerson(bmp: Bitmap): Bitmap? {
        return try {
            segmentPersonTflite(bmp)
        } catch (e: Exception) {
            Log.w(TAG, "TFLite 抠图失败，回退 MediaPipe", e)
            segmentPersonMediaPipe(bmp)
        }
    }

    /** TFLite 原生推理（u2net_human_seg），返回带 alpha 的抠图位图 */
    private fun segmentPersonTflite(bmp: Bitmap): Bitmap? {
        val interpreter = Interpreter(loadModelMapped())
        return try {
            // u2net_human_seg 输入 1xHxWx3（NHWC），固定边长；保持长宽比 letterbox 填充
            val inSize = U2NET_INPUT_SIZE // 320
            val (inBmp, offX, offY, usedW, usedH) = resizeKeepRatio(bmp, inSize)
            val input = preprocessU2net(inBmp, inSize) // FloatArray [1,H,W,3]，ImageNet 归一化
            // 输出 1xHxWx1 单通道 0~1（已 sigmoid）
            val out = Array(1) { Array(inSize) { Array(inSize) { FloatArray(1) } } }
            interpreter.run(input, out)
            // 取出有效区内单通道 mask 为一维数组（仅人物绘制区，排除黑边）
            val mask = FloatArray(usedW * usedH)
            for (y in 0 until usedH) for (x in 0 until usedW) {
                mask[y * usedW + x] = out[0][offY + y][offX + x][0]
            }
            // 把有效区 mask 映射回原图（按等比缩放比例，避免 letterbox 黑边导致错位）
            applyMask(bmp, mask, usedW, usedH)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite 人像推理失败", e)
            null
        } finally {
            interpreter.close()
        }
    }

    /** MediaPipe Selfie Segmenter 兜底路径（原实现） */
    private fun segmentPersonMediaPipe(bmp: Bitmap): Bitmap? {
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("selfie_segmenter.tflite")
                    .setDelegate(Delegate.GPU)  // GPU 下 MediaPipe 会把 mask 结果回拷为 CPU ByteBuffer，便于读取像素
                    .build()
            )
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .build()
        val segmenter = ImageSegmenter.createFromOptions(this, options)
        return try {
            val mpImage = BitmapImageBuilder(bmp).build()
            val result = segmenter.segment(mpImage)
            val masks = result.confidenceMasks().orElse(emptyList())
            // selfie_segmenter 标签：0=背景 1=人像
            val mask = if (masks.size > 1) masks[1] else if (masks.isNotEmpty()) masks[0] else {
                Log.e(TAG, "分割未输出置信度 mask")
                return null
            }
            applyMask(bmp, mask)
        } catch (e: Exception) {
            Log.e(TAG, "人像分割推理失败", e)
            null
        } finally {
            segmenter.close()
        }
    }

    // ---------- TFLite 模型加载 / 预处理 ----------

    /** 从 assets 加载 TFLite 模型为 MappedByteBuffer */
    private fun loadModelMapped(): MappedByteBuffer {
        assets.openFd(MODEL_TFLITE).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { fc ->
                return fc.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    /**
     * 保持长宽比缩放并居中填充到 inSize×inSize（letterbox）。
     * 返回：缩放填充后的 Bitmap，以及有效区在原 inSize 画布中的偏移 (offX, offY) 和尺寸 (usedW, usedH)。
     * 仅对有效区做 mask 推理与映射，可避免黑边填充导致的轮廓错位。
     */
    private data class LetterboxResult(
        val bmp: Bitmap,
        val offX: Int,
        val offY: Int,
        val usedW: Int,
        val usedH: Int,
    )

    private fun resizeKeepRatio(src: Bitmap, inSize: Int): LetterboxResult {
        val sw = src.width
        val sh = src.height
        val scale = minOf(inSize.toFloat() / sw, inSize.toFloat() / sh)
        val dw = (sw * scale).toInt().coerceAtLeast(1)
        val dh = (sh * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, dw, dh, true)
        val offX = (inSize - dw) / 2
        val offY = (inSize - dh) / 2
        // 居中贴到 inSize×inSize 的黑色画布（黑边区域模型输出背景，不参与 mask 映射）
        val canvasBmp = Bitmap.createBitmap(inSize, inSize, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(canvasBmp)
        c.drawColor(android.graphics.Color.BLACK)
        c.drawBitmap(scaled, offX.toFloat(), offY.toFloat(), null)
        scaled.recycle()
        return LetterboxResult(canvasBmp, offX, offY, dw, dh)
    }

    /**
     * u2net_human_seg 预处理：ARGB_8888 → NHWC FloatArray [1,H,W,3]。
     * 使用 ImageNet mean/std 归一化（u2net 官方做法），范围约 [-2,2]。
     * 若抠图全黑/全白，说明模型导出已含归一化，需改回 x/255。
     */
    private fun preprocessU2net(bmp: Bitmap, inSize: Int): Array<Array<Array<FloatArray>>> {
        val px = IntArray(inSize * inSize)
        bmp.getPixels(px, 0, inSize, 0, 0, inSize, inSize)
        val input = Array(1) {
            Array(inSize) { Array(inSize) { FloatArray(3) } }
        }
        for (y in 0 until inSize) {
            for (x in 0 until inSize) {
                val p = px[y * inSize + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // ImageNet 归一化
                input[0][y][x][0] = (r / 255f - 0.485f) / 0.229f
                input[0][y][x][1] = (g / 255f - 0.456f) / 0.224f
                input[0][y][x][2] = (b / 255f - 0.406f) / 0.225f
            }
        }
        return input
    }

    /** 把分割 mask（0~1 置信度）转成 alpha，生成透明背景位图（双线性缩放） */
    private fun applyMask(bmp: Bitmap, mask: MPImage): Bitmap {
        val mw = mask.width
        val mh = mask.height
        val n = mw * mh
        val buf = maskBuffer(mask)
        buf.order(ByteOrder.nativeOrder())
        buf.rewind()
        val floats = FloatArray(n)
        val remaining = buf.remaining()
        when {
            // FLOAT32
            remaining >= n * 4 -> buf.asFloatBuffer().get(floats)
            // FLOAT16
            remaining >= n * 2 -> for (i in 0 until n) floats[i] = halfToFloat(buf.short)
            // UINT8
            else -> for (i in 0 until n) floats[i] = (buf.get().toInt() and 0xFF) / 255f
        }
        return applyMask(bmp, floats, mw, mh)
    }

    /** applyMask 核心：float mask 双线性缩放 + alpha 合成（两条推理路径共用） */
    private fun applyMask(bmp: Bitmap, mask: FloatArray, mw: Int, mh: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        // mask 缩放到原图尺寸（双线性）
        val alpha = FloatArray(w * h)
        val sxScale = (mw - 1).toFloat() / (w - 1).toFloat()
        val syScale = (mh - 1).toFloat() / (h - 1).toFloat()
        for (y in 0 until h) {
            val fy = (y * syScale).coerceAtMost(mh - 1f)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, mh - 1)
            val wy = fy - y0
            val rowBase = y * w
            for (x in 0 until w) {
                val fx = (x * sxScale).coerceAtMost(mw - 1f)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, mw - 1)
                val wx = fx - x0
                val v = mask[y0 * mw + x0] * (1 - wx) * (1 - wy) +
                    mask[y0 * mw + x1] * wx * (1 - wy) +
                    mask[y1 * mw + x0] * (1 - wx) * wy +
                    mask[y1 * mw + x1] * wx * wy
                alpha[rowBase + x] = v
            }
        }

        // 原图 RGB + mask alpha
        // 阈值硬切 + 羽化：置信度低于(THRESHOLD-FEATHER)当背景(全透明)，
        // 高于(THRESHOLD+FEATHER)当前景(不透明)，中间线性过渡，消除半透明残影。
        val lo = MASK_THRESHOLD - MASK_FEATHER
        val hi = MASK_THRESHOLD + MASK_FEATHER
        val span = (hi - lo).coerceAtLeast(1e-4f)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val raw = alpha[i]
            val a = when {
                raw <= lo -> 0f
                raw >= hi -> 1f
                else -> (raw - lo) / span
            }
            val ai = (a * 255f).toInt().coerceIn(0, 255)
            px[i] = (px[i] and 0x00FFFFFF) or (ai shl 24)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 读取 mask 像素 Buffer。
     * 使用 MediaPipe 官方公开的 ByteBufferExtractor.extract()，兼容 CPU / GPU / OpenCL
     * 任意存储类型，避免反射内部容器（旧实现在 0.10.35 下 mask 以 OpenCL 容器返回导致失败）。
     */
    private fun maskBuffer(mask: MPImage): ByteBuffer {
        return ByteBufferExtractor.extract(mask)
    }

    /** IEEE 754 半精度浮点转单精度（Android Half 需要 API 26，手动实现兼容 minSdk 24） */
    private fun halfToFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = if ((bits and 0x8000) != 0) -1f else 1f
        val exp = (bits shr 10) and 0x1F
        val frac = bits and 0x3FF
        return when {
            exp == 0 -> sign * (frac / 1024f) * 2f.pow(1 - 15)
            exp == 31 -> if (frac == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1 + frac / 1024f) * 2f.pow(exp - 15)
        }
    }

    // ---------- 图片解码（含 EXIF 旋转修正、按目标边长降采样） ----------
    // 注意1：inJustDecodeBounds=true 时 BitmapFactory.decodeStream 恒返回 null（只填尺寸），
    //       绝不能把返回值当失败判断 —— 这是之前"合成失败"的根因。
    // 注意2：EXIF 读取对 HEIC/HEIF 等格式可能抛异常，必须单独容错。
    private fun decode(uri: Uri, targetSide: Int): Bitmap? {
        val tag = uri.lastPathSegment ?: uri.toString()
        return try {
            // 阶段1：读尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e(TAG, "无法读取图片尺寸: $tag (w=${bounds.outWidth} h=${bounds.outHeight})")
                return null
            }

            // 阶段2：按目标边长降采样解码
            var sample = 1
            while (bounds.outWidth / sample > targetSide || bounds.outHeight / sample > targetSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: run {
                Log.e(TAG, "图片解码失败(采样阶段): $tag sample=$sample")
                return null
            }

            // 阶段3：EXIF 旋转标记读取，失败就当正常方向，绝不影响主图
            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                Log.w(TAG, "EXIF 读取失败（可能是 HEIC 等格式）：$tag", e)
                ExifInterface.ORIENTATION_NORMAL
            }

            // 阶段4：按 EXIF 旋转转正
            try {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bmp, 90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bmp, 180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bmp, 270f)
                    else -> bmp
                }
            } catch (e: Exception) {
                Log.w(TAG, "图片旋转失败，使用原方向", e)
                bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "图片解码异常: $tag", e)
            null
        }
    }

    private fun rotate(bmp: Bitmap, deg: Float): Bitmap {
        val m = Matrix().apply { postRotate(deg) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    // ---------- 拼接合成 ----------
    private fun merge() {
        if (images.isEmpty()) {
            toast(getString(R.string.err_no_images))
            return
        }
        val mode = modeSpinner.selectedItemPosition
        val cols = spCols.selectedItem.toString().toInt()
        val gap = seekGap.progress
        val radius = seekRadius.progress
        val bg = colors[selectedColor]
        val btn = btnMerge
        btn.isEnabled = false
        btn.text = getString(R.string.merging)

        // 合成放后台线程，避免大图卡 UI
        Thread {
            val bmp = try {
                mergeImages(mode, gap, radius, bg)
            } catch (e: Exception) {
                Log.e(TAG, "合成异常", e)
                null
            }
            runOnUiThread {
                btn.isEnabled = true
                btn.setText(R.string.merge_save)
                if (bmp != null) {
                    showPreviewDialog(bmp)
                } else {
                    toast(getString(R.string.merge_fail))
                }
            }
        }.start()
    }

    private fun mergeImages(mode: Int, gap: Int, radius: Int, bg: Int): Bitmap? {
        val list = images.mapNotNull { decode(it, MAX_SIDE) }
        if (list.isEmpty()) {
            Log.e(TAG, "所有图片解码失败")
            return null
        }
        val out = when (mode) {
            MODE_VER -> mergeVertically(list, gap, radius, bg)
            MODE_GRID -> mergeGrid(list, gap, radius, bg)
            MODE_SMART -> mergeSmart(list, bg)
            else -> mergeHorizontally(list, gap, radius, bg)
        }
        // 若 out 恰好是列表中的原图引用（单图场景），不能回收它
        list.forEach { if (it !== out && !it.isRecycled) it.recycle() }
        return out
    }

    /** 整图圆角裁剪工具：radius>0 时给整个画布画圆角 mask */
    private fun clipRound(c: Canvas, w: Int, h: Int, radius: Int) {
        if (radius > 0) {
            val path = Path().apply {
                addRoundRect(
                    RectF(0f, 0f, w.toFloat(), h.toFloat()),
                    radius.toFloat(), radius.toFloat(), Path.Direction.CW
                )
            }
            c.clipPath(path)
        }
    }

    /** 横向拼接：统一高度对齐，宽度累加 */
    private fun mergeHorizontally(list: List<Bitmap>, gap: Int, radius: Int, bg: Int): Bitmap {
        var h = list.maxOf { it.height }
        var ws = list.map { it.width * h / it.height }
        val naturalW = ws.sum() + gap * (list.size - 1)
        if (naturalW > MAX_DIM) {
            val k = MAX_DIM.toFloat() / naturalW
            h = (h * k).toInt()
            ws = ws.map { (it * k).toInt() }
        }
        val totalW = ws.sum() + gap * (list.size - 1)
        val out = Bitmap.createBitmap(totalW, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        clipRound(c, totalW, h, radius)
        var x = 0
        list.forEachIndexed { i, b ->
            val w = ws[i]
            val scaled = if (w != b.width || h != b.height) {
                Bitmap.createScaledBitmap(b, w, h, true)
            } else {
                b
            }
            c.drawBitmap(scaled, x.toFloat(), 0f, null)
            x += w + gap
            if (scaled !== b) scaled.recycle()
        }
        return out
    }

    /** 纵向拼接：统一宽度对齐，高度累加 */
    private fun mergeVertically(list: List<Bitmap>, gap: Int, radius: Int, bg: Int): Bitmap {
        var w = list.maxOf { it.width }
        var hs = list.map { it.height * w / it.width }
        val naturalH = hs.sum() + gap * (list.size - 1)
        if (naturalH > MAX_DIM) {
            val k = MAX_DIM.toFloat() / naturalH
            w = (w * k).toInt()
            hs = hs.map { (it * k).toInt() }
        }
        val totalH = hs.sum() + gap * (list.size - 1)
        val out = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        clipRound(c, w, totalH, radius)
        var y = 0
        list.forEachIndexed { i, b ->
            val h = hs[i]
            val scaled = if (h != b.height || w != b.width) {
                Bitmap.createScaledBitmap(b, w, h, true)
            } else {
                b
            }
            c.drawBitmap(scaled, 0f, y.toFloat(), null)
            y += h + gap
            if (scaled !== b) scaled.recycle()
        }
        return out
    }

    /** 网格拼接：正方形格子，图片中心裁剪铺满，每格可圆角 */
    private fun mergeGrid(list: List<Bitmap>, gap: Int, radius: Int, bg: Int): Bitmap {
        val userCols = spCols.selectedItem.toString().toInt()
        // 图片张数少于列数时按图片数排（保证画布尺寸与摆放一致）
        val cols = userCols.coerceIn(1, list.size)
        val rows = ceil(list.size.toDouble() / cols).toInt()
        val ow = cols * CELL + gap * (cols - 1)
        val oh = rows * CELL + gap * (rows - 1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)

        list.forEachIndexed { i, b ->
            val col = i % cols
            val row = i / cols
            val x = col * (CELL + gap)
            val y = row * (CELL + gap)
            // 等比放大铺满格子，再裁掉溢出部分（center-crop）
            val scale = max(CELL.toFloat() / b.width, CELL.toFloat() / b.height)
            val sw = (b.width * scale + 0.5f).toInt()
            val sh = (b.height * scale + 0.5f).toInt()
            val scaled = if (sw == b.width && sh == b.height) b
            else Bitmap.createScaledBitmap(b, sw, sh, true)
            val sx = (sw - CELL) / 2
            val sy = (sh - CELL) / 2
            if (radius > 0) {
                val path = Path().apply {
                    addRoundRect(
                        RectF(x.toFloat(), y.toFloat(), (x + CELL).toFloat(), (y + CELL).toFloat()),
                        radius.toFloat(), radius.toFloat(), Path.Direction.CW
                    )
                }
                c.save()
                c.clipPath(path)
                c.drawBitmap(
                    scaled,
                    Rect(sx, sy, sx + CELL, sy + CELL),
                    Rect(x, y, x + CELL, y + CELL),
                    null
                )
                c.restore()
            } else {
                c.drawBitmap(
                    scaled,
                    Rect(sx, sy, sx + CELL, sy + CELL),
                    Rect(x, y, x + CELL, y + CELL),
                    null
                )
            }
            if (scaled !== b) scaled.recycle()
        }
        return out
    }

    // ---------- 智能长图拼接（重叠行检测，适合截图拼接） ----------
    /** 在 top 底部与 bottom 顶部之间寻找最佳重叠行数，未检测到重叠返回 0 */
    private fun findOverlap(top: Bitmap, bottom: Bitmap): Int {
        val SW = 160
        val sh1 = (top.height * SW.toLong() / top.width).toInt().coerceAtLeast(1)
        val sh2 = (bottom.height * SW.toLong() / bottom.width).toInt().coerceAtLeast(1)
        val st = Bitmap.createScaledBitmap(top, SW, sh1, true)
        val sb = Bitmap.createScaledBitmap(bottom, SW, sh2, true)

        val tmplLen = minOf(40, st.height / 4)          // 模板行数：顶部图底部 40 行内
        val searchMax = minOf(sh2 / 2, 800)             // 在底部图前半部分搜索
        val len = SW * tmplLen
        val tmpl = IntArray(len)
        st.getPixels(tmpl, 0, SW, 0, st.height - tmplLen, SW, tmplLen)

        val area = IntArray(SW * (searchMax + tmplLen))
        sb.getPixels(area, 0, SW, 0, 0, SW, minOf(searchMax + tmplLen, sh2))

        var bestPos = 0
        var bestScore = Long.MAX_VALUE
        for (y in 0..searchMax) {
            var score = 0L
            for (dy in 0 until tmplLen) {
                val ty = y + dy
                if (ty >= sh2) break
                val baseT = dy * SW
                val baseA = ty * SW
                for (x in 0 until SW) {
                    val c1 = tmpl[baseT + x]
                    val c2 = area[baseA + x]
                    score += abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
                    score += abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
                    score += abs((c1 and 0xFF) - (c2 and 0xFF))
                }
            }
            if (score < bestScore) {
                bestScore = score
                bestPos = y
            }
        }
        st.recycle()
        sb.recycle()
        // 每通道平均差 < 30 才认定是真重叠（同屏截图通常 < 8）；否则退化为 0（普通纵向拼接）
        val perChannel = bestScore.toDouble() / (SW * tmplLen * 3)
        return if (perChannel < 30.0) {
            (bestPos.toLong() * bottom.height / sh2).toInt().coerceIn(0, bottom.height / 2)
        } else {
            0
        }
    }

    /** 智能长图拼接：统一宽度后逐对找重叠缝，无缝衔接 */
    private fun mergeSmart(list: List<Bitmap>, bg: Int): Bitmap {
        val wMax = list.maxOf { it.width }
        val scaled = list.map { b ->
            if (b.width == wMax) b
            else Bitmap.createScaledBitmap(
                b, wMax, (b.height * wMax.toLong() / b.width).toInt(), true
            )
        }
        val created = mutableListOf<Bitmap>()
        var cur = scaled[0]
        for (i in 1 until scaled.size) {
            val next = scaled[i]
            val overlap = findOverlap(cur, next)
            val h = cur.height + next.height - overlap
            val out = Bitmap.createBitmap(wMax, h, Bitmap.Config.ARGB_8888)
            created += out
            val c = Canvas(out)
            c.drawColor(bg)
            c.drawBitmap(cur, 0f, 0f, null)
            c.drawBitmap(next, 0f, (cur.height - overlap).toFloat(), null)
            cur = out
        }
        scaled.forEach { if (it !== cur && !it.isRecycled) it.recycle() }
        created.forEach { if (it !== cur && !it.isRecycled) it.recycle() }
        // 总高度上限保护（防 OOM）
        if (cur.height > MAX_LONG) {
            val k = MAX_LONG.toFloat() / cur.height
            val out = Bitmap.createScaledBitmap(
                cur, (cur.width * k).toInt(), MAX_LONG, true
            )
            cur.recycle()
            return out
        }
        return cur
    }

    // ---------- 保存到相册（MediaStore，Android 10+ 免权限） ----------
    private fun saveToGallery(bmp: Bitmap): Uri? {
        val name = "collage_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val dir = Environment.DIRECTORY_PICTURES + "/Collage"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, dir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(
                    MediaStore.Images.Media.DATA,
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        .absolutePath + "/Collage/$name"
                )
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        val ok = contentResolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: false
        if (!ok) {
            contentResolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    // ---------- 拼接待定：先预览，用户决定保存/分享 ----------
    // 只有用户点「保存到相册」才写 MediaStore，避免随手合成污染相册。
    private fun showPreviewDialog(bmp: Bitmap) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(
                iv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.55).toInt()
                )
            )
        }
        var consumed = false
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.preview_title))
            .setView(container)
            .setPositiveButton(getString(R.string.save_to_gallery)) { _, _ ->
                consumed = true
                val uri = try {
                    saveToGallery(bmp)
                } catch (e: Exception) {
                    Log.e(TAG, "保存异常", e)
                    null
                }
                toast(if (uri != null) getString(R.string.saved_ok) else getString(R.string.saved_fail))
                bmp.recycle()
            }
            .setNeutralButton(getString(R.string.share)) { _, _ ->
                consumed = true
                shareBitmap(bmp)
                bmp.recycle()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { if (!consumed) bmp.recycle() }
            .show()
    }

    private fun shareBitmap(bmp: Bitmap) {
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "collage_share.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Exception) {
            toast(getString(R.string.saved_fail))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}