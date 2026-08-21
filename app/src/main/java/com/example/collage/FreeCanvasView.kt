package com.example.collage

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.FrameLayout
import java.io.InputStream
import kotlin.math.*

class FreeCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        const val HANDLE = 22f   // 控制点视觉半径(逻辑坐标)
    }

    /** 画板逻辑尺寸（可配置），默认 1080 正方形 */
    var logicalW: Float = 1080f
    var logicalH: Float = 1080f

    var bgColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** 标记用户是否手动设置过画板尺寸；未设置时默认跟随屏幕分辨率 */
    private var userSetSize = false
    val elements = mutableListOf<CanvasElement>()
    var selected: CanvasElement? = null

    /** 画布整体缩放（由底部 SeekBar / 手势控制），1f = 100% */
    var canvasScale = 1f

    /** 选中的元素变化时回调（用于右侧面板刷新） */
    var onSelectionChanged: ((CanvasElement?) -> Unit)? = null

    private var scale = 1f           // 逻辑坐标 -> 像素
    private var dragging: CanvasElement? = null
    private var mode = "none"        // none|move|rotate_scale
    private var lastX = 0f
    private var lastY = 0f
    private var activeHandle = ""
    private var startDist = 1f
    private var startW = 0f
    private var startH = 0f
    private var startRot = 0f
    private var startAngle = 0f
    private var startCx = 0f
    private var startCy = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (lx, ly) = toLogical(e.x, e.y)
            val hit = hitTest(lx, ly)
            select(hit)
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!userSetSize) useScreenResolution()
        recomputeScale()
    }

    private fun recomputeScale() {
        if (logicalW <= 0f || logicalH <= 0f) return
        scale = min(width / logicalW, height / logicalH)
    }

    private fun toLogical(px: Float, py: Float): Pair<Float, Float> {
        val cx = width / 2f
        val cy = height / 2f
        val lx = (px - cx) / (scale * canvasScale) + logicalW / 2f
        val ly = (py - cy) / (scale * canvasScale) + logicalH / 2f
        return lx to ly
    }

    private fun toScreen(lx: Float, ly: Float): Pair<Float, Float> {
        val cx = width / 2f
        val cy = height / 2f
        val sx = (lx - logicalW / 2f) * (scale * canvasScale) + cx
        val sy = (ly - logicalH / 2f) * (scale * canvasScale) + cy
        return sx to sy
    }

    private fun hitTest(lx: Float, ly: Float): CanvasElement? {
        // 优先命中选中元素的控制点
        selected?.let { el ->
            if (el.locked) return@let
            handleAt(el, lx, ly)?.let { return el }
        }
        // 从上层往下命中元素
        for (i in elements.size - 1 downTo 0) {
            val el = elements[i]
            if (el.locked) continue
            if (el.contains(lx, ly)) return el
        }
        return null
    }

    private fun handleAt(el: CanvasElement, lx: Float, ly: Float): String? {
        val r = el.bounds()
        val cand = listOf(
            "tl" to Pair(r.left, r.top),
            "tr" to Pair(r.right, r.top),
            "bl" to Pair(r.left, r.bottom),
            "br" to Pair(r.right, r.bottom),
            "rot" to Pair(r.centerX(), r.top - 60f)
        )
        for ((name, p) in cand) {
            if (abs(lx - p.first) < HANDLE * 1.6f && abs(ly - p.second) < HANDLE * 1.6f) return name
        }
        return null
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) {
            // 双指：缩放 + 旋转选中元素
            val (lx, ly) = toLogical(ev.getX(0), ev.getY(0))
            val el = selected ?: return gestureDetector.onTouchEvent(ev)
            if (el.locked) return true
            val dx = ev.getX(1) - ev.getX(0)
            val dy = ev.getY(1) - ev.getY(0)
            val dist = hypot(dx, dy)
            val angle = atan2(dy, dx)
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    startDist = dist; startW = el.w; startH = el.h; startRot = el.rotation
                    startAngle = angle
                }
                MotionEvent.ACTION_MOVE -> {
                    val ratio = dist / startDist
                    el.w = (startW * ratio).coerceAtLeast(40f)
                    el.h = (startH * ratio).coerceAtLeast(40f)
                    el.rotation = startRot + Math.toDegrees((angle - startAngle).toDouble()).toFloat()
                    invalidate()
                }
            }
            return true
        }

        gestureDetector.onTouchEvent(ev)
        val (lx, ly) = toLogical(ev.x, ev.y)

        // 手动修正模式：把触摸事件用于绘制蒙版
        val selImg = selected as? CanvasElement.ImageElement
        if (selImg != null && selImg.inMaskEdit) {
            handleMaskTouch(ev, selImg)
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val el = hitTest(lx, ly) ?: return true
                if (el.locked) return true
                dragging = el
                selected?.let { sel ->
                    handleAt(sel, lx, ly)?.let { h ->
                        mode = if (h == "rot") "rotate" else "rotate_scale"
                        activeHandle = h
                        val c = sel.bounds()
                        startCx = c.centerX(); startCy = c.centerY()
                        startDist = hypot(lx - startCx, ly - startCy)
                        startW = sel.w; startH = sel.h
                        startRot = sel.rotation
                        startAngle = atan2(ly - startCy, lx - startCx)
                        return true
                    }
                }
                mode = "move"
                lastX = lx; lastY = ly
            }
            MotionEvent.ACTION_MOVE -> {
                val el = dragging ?: return true
                if (el.locked) return true
                when (mode) {
                    "move" -> {
                        el.x += (lx - lastX); el.y += (ly - lastY)
                        lastX = lx; lastY = ly
                    }
                    "rotate_scale" -> {
                        val dist = hypot(lx - startCx, ly - startCy)
                        val ratio = dist / startDist
                        el.w = (startW * ratio).coerceAtLeast(40f)
                        el.h = (startH * ratio).coerceAtLeast(40f)
                    }
                    "rotate" -> {
                        val ang = atan2(ly - startCy, lx - startCx)
                        el.rotation = startRot + Math.toDegrees((ang - startAngle).toDouble()).toFloat()
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null; mode = "none"
            }
        }
        return true
    }

    private fun select(el: CanvasElement?) {
        selected = el
        onSelectionChanged?.invoke(el)
        invalidate()
    }

    fun selectElement(el: CanvasElement?) {
        select(el)
        el?.let { bringHintToFront(it) }
    }

    private fun bringHintToFront(el: CanvasElement) {
        // 选中时不实际改变 zOrder，仅视觉（此处保留接口）
    }

    // ============== 手动修正（橡皮擦/画笔）==============
    enum class MaskBrush { ERASE, PAINT }

    /** 当前笔刷模式：ERASE=擦掉(把蒙版涂黑)，PAINT=补回(把蒙版涂白) */
    var brushMode: MaskBrush = MaskBrush.ERASE
    /** 笔刷半径（屏幕像素），由外部设置 */
    var brushRadius: Float = 30f
    /** 是否正在手动修正绘制中 */
    private var masking = false

    /** 进入/退出选中元素的手动修正模式 */
    fun setMaskEdit(enabled: Boolean) {
        val el = selected as? CanvasElement.ImageElement ?: return
        el.inMaskEdit = enabled
        if (enabled) {
            // 初始化一张白色蒙版（默认保留全部，等同未修正）
            if (el.userMask == null) {
                el.userMask = createInitialMask(el.bitmap)
            }
        }
        invalidate()
    }

    /** 退出手动修正模式（保留已绘制结果） */
    fun exitMaskEdit() {
        (selected as? CanvasElement.ImageElement)?.inMaskEdit = false
        masking = false
        invalidate()
    }

    /** 清空手动修正，恢复 AI 原始结果 */
    fun clearMaskEdit() {
        val el = selected as? CanvasElement.ImageElement ?: return
        el.userMask?.recycle()
        el.userMask = null
        el.inMaskEdit = false
        masking = false
        invalidate()
    }

    /** 创建初始全白蒙版（表示全部保留），尺寸与 bitmap 同比例缩小以省内存 */
    private fun createInitialMask(bitmap: Bitmap): Bitmap {
        // 限制蒙版最大边为 512，笔刷在缩略蒙版上绘制，合成时映射到原图
        val maxEdge = 512
        val bw = bitmap.width
        val bh = bitmap.height
        val ratio = if (maxOf(bw, bh) > maxEdge) maxEdge.toFloat() / maxOf(bw, bh) else 1f
        val mw = max(1, (bw * ratio).toInt())
        val mh = max(1, (bh * ratio).toInt())
        val mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        val c = Canvas(mask)
        c.drawColor(Color.WHITE) // 全白 = 全部保留
        return mask
    }

    /**
     * 在 [el] 的 userMask 上以 (mx,my) 画一笔。坐标基于元素屏幕矩形。
     * ERASE 用黑色(0)合成，PAINT 用白色(255)合成，实现局部保留/擦除。
     */
    private fun paintMaskStroke(el: CanvasElement.ImageElement, mx: Float, my: Float) {
        val mask = el.userMask ?: return
        // 在 ALPHA_8 画布上，绘制颜色只取 alpha 通道。
        // 关键：默认 source-over 混色下，画"透明"是空操作(擦不掉)，
        // 因此 ERASE 必须用 PorterDuff CLEAR 模式才能真正把像素清零。
        val erase = brushMode == MaskBrush.ERASE
        // 屏幕坐标 -> 蒙版像素坐标
        val (tlx, tly) = toScreen(el.x, el.y)
        val w = el.w * scale * canvasScale
        val h = el.h * scale * canvasScale
        val fx = ((mx - tlx) / w)
        val fy = ((my - tly) / h)
        if (fx < -0.1f || fx > 1.1f || fy < -0.1f || fy > 1.1f) return
        val px = (fx * mask.width).toInt().coerceIn(0, mask.width - 1)
        val py = (fy * mask.height).toInt().coerceIn(0, mask.height - 1)
        // 笔刷半径映射到蒙版像素
        val rPx = (brushRadius / w * mask.width).coerceAtLeast(1f)
        val c = Canvas(mask)
        val p = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            if (erase) {
                // CLEAR：用源 alpha 作为清除强度，径向渐变实现软边擦除
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } // PAINT：默认 source-over，白色渐变叠加到透明区即可补回
        }
        // 中心 alpha=255(实心)，向边缘渐隐到 0(软边)
        val center = if (erase) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        val grad = RadialGradient(px.toFloat(), py.toFloat(), rPx, center, 0x00000000.toInt(), Shader.TileMode.CLAMP)
        p.shader = grad
        c.drawCircle(px.toFloat(), py.toFloat(), rPx, p)
    }

    /** 手动修正模式的触摸处理：按下/移动时在蒙版上绘制 */
    private fun handleMaskTouch(ev: MotionEvent, el: CanvasElement.ImageElement) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                masking = true
                lastBrushX = ev.x; lastBrushY = ev.y
                paintMaskStroke(el, ev.x, ev.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!masking) return
                // 插值多段，避免快速移动出现断点
                val (sx, sy) = Pair(ev.x, ev.y)
                lastBrushX = sx; lastBrushY = sy
                val hist = ev.historySize
                if (hist > 0) {
                    for (i in 0 until hist) paintMaskStroke(el, ev.getHistoricalX(i), ev.getHistoricalY(i))
                }
                paintMaskStroke(el, sx, sy)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                masking = false
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // 画布外底色
        canvas.drawColor(0xFFE9EAEC.toInt())

        // 裁剪到画布逻辑区域并填充画板背景色
        val (bx, by) = toScreen(0f, 0f)
        val sizeW = logicalW * scale * canvasScale
        val sizeH = logicalH * scale * canvasScale
        canvas.save()
        canvas.clipRect(bx, by, bx + sizeW, by + sizeH)
        val bgPaint = Paint().apply { color = bgColor }
        canvas.drawRect(bx, by, bx + sizeW, by + sizeH, bgPaint)

        val sorted = elements.sortedBy { it.zOrder }
        for (el in sorted) drawElement(canvas, el)
        canvas.restore()

        // 画板边界线
        val borderPaint = Paint().apply {
            color = 0xFFCCCCCC.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRect(bx, by, bx + sizeW, by + sizeH, borderPaint)

        // 选中框 + 控制点（不裁剪）
        selected?.let { drawSelection(canvas, it) }

        // 手动修正模式：显示蒙版预览 + 笔刷光标
        (selected as? CanvasElement.ImageElement)?.takeIf { it.inMaskEdit }?.let { drawMaskEditOverlay(canvas, it) }
    }

    private var lastBrushX = 0f
    private var lastBrushY = 0f
    private fun drawMaskEditOverlay(canvas: Canvas, el: CanvasElement.ImageElement) {
        val (tlx, tly) = toScreen(el.x, el.y)
        val w = el.w * scale * canvasScale
        val h = el.h * scale * canvasScale
        // 在元素区域上叠一层半透明遮罩，提示"可编辑"
        val overlay = Paint().apply { color = 0x22000000.toInt(); style = Paint.Style.FILL }
        canvas.drawRect(tlx, tly, tlx + w, tly + h, overlay)
        // 笔刷光标
        val cur = Paint().apply {
            color = if (brushMode == MaskBrush.ERASE) 0xFFE53935.toInt() else 0xFF43A047.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(lastBrushX, lastBrushY, brushRadius, cur)
    }

    private fun drawElement(canvas: Canvas, el: CanvasElement) {
        val (sx, sy) = toScreen(el.x, el.y)
        val w = el.w * scale * canvasScale
        val h = el.h * scale * canvasScale
        canvas.save()
        canvas.translate(sx + w / 2f, sy + h / 2f)
        canvas.rotate(el.rotation)
        canvas.saveLayerAlpha(-w / 2f, -h / 2f, w / 2f, h / 2f, (el.alpha * 255).toInt(), Canvas.ALL_SAVE_FLAG)
        when (el) {
            is CanvasElement.ImageElement -> {
                var bmp = applyEffects(el)
                if (el.userMask != null) bmp = composeUserMask(el, bmp)
                val drawable = BitmapDrawable(resources, bmp)
                drawable.setBounds(-(w / 2f).toInt(), -(h / 2f).toInt(), (w / 2f).toInt(), (h / 2f).toInt())
                drawable.draw(canvas)
            }
            is CanvasElement.TextElement -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = el.color
                    textSize = el.textSizeSp * scale * canvasScale
                    textAlign = Paint.Align.CENTER
                }
                val font = paint.fontMetrics
                canvas.drawText(el.text, 0f, -(font.ascent + font.descent) / 2f, paint)
            }
        }
        canvas.restore()
        canvas.restore()
    }

    /** 阶段4：应用滤镜 + 蒙版（带缓存避免每帧重算） */
    private val effectCache = mutableMapOf<CanvasElement.ImageElement, Pair<Pair<ImageFilter, MaskShape>, Bitmap>>()
    private fun applyEffects(el: CanvasElement.ImageElement): Bitmap {
        val key = el.filter to el.mask
        val cached = effectCache[el]
        if (cached != null && cached.first == key) return cached.second
        var bmp = el.bitmap
        if (el.filter != ImageFilter.NONE) bmp = ImageEffects.applyFilter(bmp, el.filter)
        if (el.mask != MaskShape.NONE) bmp = ImageEffects.applyMask(bmp, el.mask)
        // 释放旧缓存
        cached?.let { if (it.second != el.bitmap) {} }
        effectCache[el] = key to bmp
        return bmp
    }

    /**
     * 将用户手动修正蒙版合成到已渲染的 bitmap 上。
     * userMask 为单通道灰度（0=透明，255=不透明），与 bitmap 同尺寸，
     * 这里把 bitmap 每个像素的 alpha 与 mask 对应像素相乘后再映射回 0..255。
     */
    private fun composeUserMask(el: CanvasElement.ImageElement, src: Bitmap): Bitmap {
        val mask = el.userMask ?: return src
        if (mask.width <= 0 || mask.height <= 0) return src
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h); src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val maskPx = IntArray(mask.width * mask.height); mask.getPixels(maskPx, 0, mask.width, 0, 0, mask.width, mask.height)
        val mw = mask.width; val mh = mask.height
        for (y in 0 until h) {
            val my = (y * mh / h).coerceIn(0, mh - 1)
            for (x in 0 until w) {
                val mx = (x * mw / w).coerceIn(0, mw - 1)
                val m = ((maskPx[my * mw + mx] ushr 24) and 0xFF) / 255f
                val p = srcPx[y * w + x]
                val a = (Color.alpha(p) * m + 0.5f).toInt().coerceIn(0, 255)
                srcPx[y * w + x] = (a shl 24) or (p and 0x00FFFFFF)
            }
        }
        out.setPixels(srcPx, 0, w, 0, 0, w, h)
        return out
    }

    private fun drawSelection(canvas: Canvas, el: CanvasElement) {
        val r = el.bounds()
        val (tlx, tly) = toScreen(r.left, r.top)
        val (brx, bry) = toScreen(r.right, r.bottom)
        val w = brx - tlx
        val h = bry - tly
        val cx = (tlx + brx) / 2f
        val cy = (tly + bry) / 2f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(el.rotation)
        val paint = Paint().apply { color = Color.parseColor("#3F51B5"); style = Paint.Style.STROKE; strokeWidth = 2f }
        canvas.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, paint)
        // 控制点
        val hs = HANDLE * scale * canvasScale
        val pts = listOf(
            -w / 2f to -h / 2f, w / 2f to -h / 2f, -w / 2f to h / 2f, w / 2f to h / 2f
        )
        val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val stroke = Paint().apply { color = Color.parseColor("#3F51B5"); style = Paint.Style.STROKE; strokeWidth = 2f }
        for ((px, py) in pts) {
            canvas.drawCircle(px, py, hs, fill)
            canvas.drawCircle(px, py, hs, stroke)
        }
        // 旋转手柄
        canvas.drawLine(0f, -h / 2f, 0f, -h / 2f - hs * 2, paint)
        canvas.drawCircle(0f, -h / 2f - hs * 2, hs, fill)
        canvas.drawCircle(0f, -h / 2f - hs * 2, hs, stroke)
        canvas.restore()
    }

    // ---------- 对外操作 API ----------

    fun addImage(uri: Uri, bmp: Bitmap) {
        val side = logicalW * 0.4f
        val w = side
        val h = side / (bmp.width.toFloat() / bmp.height)
        val el = CanvasElement.ImageElement(
            uri, bmp,
            x = (logicalW - w) / 2f,
            y = (logicalH - h) / 2f,
            w = w, h = h, rotation = 0f
        )
        el.zOrder = (elements.maxOfOrNull { it.zOrder } ?: 0) + 1
        elements.add(el)
        select(el)
        invalidate()
    }

    fun rebindImage(uri: Uri, bmp: Bitmap) {
        val el = selected as? CanvasElement.ImageElement ?: return
        elements[elements.indexOf(el)] = el.copy(uri = uri, bitmap = bmp)
        select(elements[elements.indexOf(el)])
        invalidate()
    }

    fun setElementAlpha(a: Float) {
        selected?.let { it.alpha = a; invalidate() }
    }

    fun toggleLock(): Boolean {
        selected?.let { it.locked = !it.locked; invalidate(); return it.locked }
        return false
    }

    fun deleteSelected() {
        selected?.let { elements.remove(it); select(null); invalidate() }
    }

    fun bringToFrontLayer() {
        selected?.let { it.zOrder = (elements.maxOfOrNull { e -> e.zOrder } ?: 0) + 1; invalidate() }
    }

    fun sendToBack() {
        selected?.let { it.zOrder = (elements.minOfOrNull { e -> e.zOrder } ?: 0) - 1; invalidate() }
    }

    fun moveLayerUp() {
        selected?.let { it.zOrder += 1; invalidate() }
    }

    fun moveLayerDown() {
        selected?.let { it.zOrder -= 1; invalidate() }
    }

    /** 阶段3：随机打散自由布局 */
    fun randomizeLayout() {
        for (el in elements) {
            if (el.locked) continue
            el.x = (Math.random() * logicalW * 0.5f).toFloat()
            el.y = (Math.random() * logicalH * 0.5f).toFloat()
            el.rotation = (Math.random() * 40 - 20).toFloat()
            val s = 0.3f + Math.random().toFloat() * 0.4f
            el.w = logicalW * s
            el.h = el.w / el.aspect()
        }
        invalidate()
    }

    // ---------- 对齐 / 分布 ----------
    private fun selectedOrAll(): List<CanvasElement> {
        val sel = selected
        return if (sel != null) listOf(sel) else elements.toList()
    }

    private fun group(): List<CanvasElement> = if (elements.size > 1) elements else selectedOrAll()

    fun alignLeft() { val g = group(); if (g.isEmpty()) return; val l = g.minOf { it.bounds().left }; g.forEach { it.x = l }; invalidate() }
    fun alignRight() { val g = group(); if (g.isEmpty()) return; val r = g.maxOf { it.bounds().right }; g.forEach { it.x = r - it.w }; invalidate() }
    fun alignTop() { val g = group(); if (g.isEmpty()) return; val t = g.minOf { it.bounds().top }; g.forEach { it.y = t }; invalidate() }
    fun alignBottom() { val g = group(); if (g.isEmpty()) return; val b = g.maxOf { it.bounds().bottom }; g.forEach { it.y = b - it.h }; invalidate() }
    fun alignCenterH() { val g = group(); if (g.isEmpty()) return; val c = g.sumOf { it.bounds().centerX().toDouble() }.toFloat() / g.size; g.forEach { it.x = c - it.w / 2f }; invalidate() }
    fun alignCenterV() { val g = group(); if (g.isEmpty()) return; val c = g.sumOf { it.bounds().centerY().toDouble() }.toFloat() / g.size; g.forEach { it.y = c - it.h / 2f }; invalidate() }

    fun distributeH() {
        val g = group().sortedBy { it.bounds().centerX() }
        if (g.size < 3) return
        val left = g.first().bounds().left
        val right = g.last().bounds().right
        val totalW = g.sumOf { it.w.toDouble() }.toFloat()
        val gap = (right - left - totalW) / (g.size - 1)
        var cur = left
        for (el in g) { el.x = cur; cur += el.w + gap }
        invalidate()
    }

    fun distributeV() {
        val g = group().sortedBy { it.bounds().centerY() }
        if (g.size < 3) return
        val top = g.first().bounds().top
        val bottom = g.last().bounds().bottom
        val totalH = g.sumOf { it.h.toDouble() }.toFloat()
        val gap = (bottom - top - totalH) / (g.size - 1)
        var cur = top
        for (el in g) { el.y = cur; cur += el.h + gap }
        invalidate()
    }

    /** 渲染为最终合成 Bitmap（阶段2 合成复用） */
    fun renderToBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(logicalW.toInt(), logicalH.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bgColor)
        val sorted = elements.sortedBy { it.zOrder }
        for (el in sorted) {
            c.save()
            val cx = el.x + el.w / 2f
            val cy = el.y + el.h / 2f
            c.translate(cx, cy)
            c.rotate(el.rotation)
            c.saveLayerAlpha(-el.w / 2f, -el.h / 2f, el.w / 2f, el.h / 2f, (el.alpha * 255).toInt(), Canvas.ALL_SAVE_FLAG)
            when (el) {
                is CanvasElement.ImageElement -> {
                    var rbmp = applyEffects(el)
                    if (el.userMask != null) rbmp = composeUserMask(el, rbmp)
                    val dw = BitmapDrawable(resources, rbmp)
                    dw.setBounds(-(el.w / 2f).toInt(), -(el.h / 2f).toInt(), (el.w / 2f).toInt(), (el.h / 2f).toInt())
                    dw.draw(c)
                }
                is CanvasElement.TextElement -> {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = el.color; textSize = el.textSizeSp; textAlign = Paint.Align.CENTER }
                    val fm = p.fontMetrics
                    c.drawText(el.text, 0f, -(fm.ascent + fm.descent) / 2f, p)
                }
            }
            c.restore()
            c.restore()
        }
        return bmp
    }

    fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val `is`: InputStream? = context.contentResolver.openInputStream(uri)
            val bmp = android.graphics.BitmapFactory.decodeStream(`is`)
            `is`?.close()
            bmp
        } catch (e: Exception) { null }
    }

    /** 设置画板逻辑尺寸（像素基准 1080 时即为其边长） */
    fun setCanvasSize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        userSetSize = true
        logicalW = w
        logicalH = h
        recomputeScale()
        invalidate()
    }

    /** 将画板尺寸重置为当前屏幕分辨率 */
    fun useScreenResolution() {
        val dm = resources.displayMetrics
        userSetSize = true
        logicalW = dm.widthPixels.toFloat()
        logicalH = dm.heightPixels.toFloat()
        recomputeScale()
        invalidate()
    }
}
