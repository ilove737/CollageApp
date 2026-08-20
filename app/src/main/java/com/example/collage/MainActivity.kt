package com.example.collage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.collage.CanvasElement.ImageElement
import kotlin.math.ceil
import kotlin.math.sqrt
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var canvas: FreeCanvasView
    private lateinit var gridView: GridView
    private lateinit var modeList: LinearLayout
    private lateinit var leftListContainer: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var propContainer: LinearLayout
    private lateinit var tvNoSelection: TextView
    private lateinit var tvPanelTitle: TextView
    private lateinit var tvTitle: TextView

    private var mode = "free"   // grid | free | poster | puzzle

    private val modes = listOf(
        ModeItem("grid", R.drawable.ic_grid, R.string.mode_grid),
        ModeItem("free", R.drawable.ic_free, R.string.mode_free),
        ModeItem("poster", R.drawable.ic_poster, R.string.mode_poster),
        ModeItem("puzzle", R.drawable.ic_puzzle, R.string.mode_puzzle)
    )

    data class ModeItem(val id: String, val icon: Int, val label: Int)

    // 选图
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handlePicked(uri, false)
    }
    private val pickReplace = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = canvas.loadBitmapFromUri(uri)
            if (bmp != null) canvas.rebindImage(uri, bmp)
        }
    }
    private val pickForGrid = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) setupGrid(uris)
    }
    private val pickMultiple = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) handlePickedMultiple(uris)
    }

    private var curUris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvas = findViewById(R.id.canvas)
        gridView = findViewById(R.id.gridView)
        modeList = findViewById(R.id.modeList)
        leftListContainer = findViewById(R.id.leftListContainer)
        rightPanel = findViewById(R.id.rightPanel)
        propContainer = findViewById(R.id.propContainer)
        tvNoSelection = findViewById(R.id.tvNoSelection)
        tvPanelTitle = findViewById(R.id.tvPanelTitle)
        tvTitle = findViewById(R.id.tvTitle)

        canvas.onSelectionChanged = { refreshPropertyPanel() }

        bindBoardPanel()

        // 顶栏
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { mergeAndShare() }

        // 底部
        findViewById<View>(R.id.btnAdd).setOnClickListener { addImage() }
        findViewById<View>(R.id.btnRandom).setOnClickListener { canvas.randomizeLayout() }
        findViewById<View>(R.id.btnSegmentAll).setOnClickListener { segmentAllImages() }
        val seekZoom = findViewById<SeekBar>(R.id.seekZoom)
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                canvas.canvasScale = (p / 100f).coerceIn(0.3f, 3f)
                canvas.invalidate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // 右侧属性
        bindPropertyPanel()

        // 左侧模式导航（动态构建，避免 RecyclerView 依赖）
        buildModeList()

        selectMode("free")
        refreshPropertyPanel()
    }

    private fun buildModeList() {
        modeList.removeAllViews()
        for (m in modes) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_mode, modeList, false)
            v.findViewById<ImageView>(R.id.modeIcon).setImageResource(m.icon)
            v.findViewById<TextView>(R.id.modeLabel).setText(m.label)
            v.setBackgroundResource(if (m.id == mode) R.drawable.bg_mode_tab_sel else R.drawable.bg_mode_tab)
            v.setOnClickListener {
                mode = m.id
                for (i in 0 until modeList.childCount) {
                    modeList.getChildAt(i).setBackgroundResource(R.drawable.bg_mode_tab)
                }
                v.setBackgroundResource(R.drawable.bg_mode_tab_sel)
                selectMode(m.id)
            }
            modeList.addView(v)
        }
    }

    private fun selectMode(id: String) {
        mode = id
        tvTitle.setText(
            when (id) {
                "grid" -> R.string.mode_grid
                "free" -> R.string.mode_free
                "poster" -> R.string.mode_poster
                "puzzle" -> R.string.mode_puzzle
                else -> R.string.title_collage
            }
        )
        when (id) {
            "grid" -> {
                canvas.visibility = View.GONE
                gridView.visibility = View.VISIBLE
                buildGridTemplates()
            }
            "free" -> {
                canvas.visibility = View.VISIBLE
                gridView.visibility = View.GONE
                buildFreeAssets()
            }
            "poster" -> {
                canvas.visibility = View.VISIBLE
                gridView.visibility = View.GONE
                buildPosterTemplates()
            }
            "puzzle" -> {
                canvas.visibility = View.VISIBLE
                gridView.visibility = View.GONE
                buildPuzzleTemplates()
            }
        }
    }

    // 左侧列表：自由模式 -> 已添加图片（图层式列表）
    private fun buildFreeAssets() {
        leftListContainer.removeAllViews()
        val title = TextView(this).apply { setText(R.string.images); setTextSize(12f); setTextColor(0x888888); }
        leftListContainer.addView(title)
        if (canvas.elements.isEmpty()) {
            val empty = TextView(this).apply { setText("点击下方“添加图片”"); setTextSize(12f); setTextColor(0x9AA0A6); }
            leftListContainer.addView(empty)
            return
        }
        canvas.elements.sortedByDescending { it.zOrder }.forEachIndexed { idx, el ->
            val row = TextView(this).apply {
                text = if (el is ImageElement) "图片 ${canvas.elements.size - idx}" else "文本"
                setPadding(8, 10, 8, 10)
                setBackgroundResource(R.drawable.bg_mode_tab)
                setOnClickListener { canvas.selectElement(el); refreshPropertyPanel() }
            }
            leftListContainer.addView(row)
        }
    }

    // 左侧列表：网格模式 -> 模板缩略图（占位）
    private fun buildGridTemplates() {
        leftListContainer.removeAllViews()
        val title = TextView(this).apply { setText(R.string.templates); setTextSize(12f); setTextColor(0x888888); }
        leftListContainer.addView(title)
        val tips = TextView(this).apply { setText(R.string.templates_hint); setTextSize(11f); setTextColor(0x9AA0A6); }
        leftListContainer.addView(tips)
        val btn = Button(this).apply {
            text = "选择图片(2-9张)"
            setOnClickListener { pickForGrid.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        leftListContainer.addView(btn)
    }

    // 阶段5 海报模板
    private fun buildPosterTemplates() {
        leftListContainer.removeAllViews()
        val title = TextView(this).apply { setText(R.string.templates); setTextSize(12f); setTextColor(0x888888); }
        leftListContainer.addView(title)
        val templates = listOf(R.string.template_5, R.string.template_6)
        templates.forEach { tid ->
            val btn = Button(this).apply {
                setText(tid)
                setOnClickListener { applyPosterTemplate(getString(tid)) }
            }
            leftListContainer.addView(btn)
        }
    }

    // 阶段5 拼图模板
    private fun buildPuzzleTemplates() {
        leftListContainer.removeAllViews()
        val title = TextView(this).apply { setText(R.string.templates); setTextSize(12f); setTextColor(0x888888); }
        leftListContainer.addView(title)
        val templates = listOf(R.string.template_1, R.string.template_2, R.string.template_3, R.string.template_4)
        templates.forEach { tid ->
            val btn = Button(this).apply {
                setText(tid)
                setOnClickListener { applyPuzzleTemplate(getString(tid)) }
            }
            leftListContainer.addView(btn)
        }
    }

    // ---------- 右侧属性面板 ----------
    private fun bindPropertyPanel() {
        findViewById<View>(R.id.btnReplace).setOnClickListener { pickReplace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        findViewById<View>(R.id.btnCrop).setOnClickListener { openCrop() }
        findViewById<View>(R.id.btnFilter).setOnClickListener { openFilter() }
        findViewById<View>(R.id.btnMask).setOnClickListener { openMask() }
        findViewById<View>(R.id.btnSegment).setOnClickListener { openSegment() }
        // 手动修正：进入/退出蒙版编辑
        findViewById<View>(R.id.btnRefine).setOnClickListener {
            val el = canvas.selected as? ImageElement ?: return@setOnClickListener
            val enter = !el.inMaskEdit
            canvas.setMaskEdit(enter)
            refreshRefineUI(enter)
            if (enter) refreshPropertyPanel() else refreshPropertyPanel()
        }
        findViewById<View>(R.id.btnErase).setOnClickListener { canvas.brushMode = FreeCanvasView.MaskBrush.ERASE }
        findViewById<View>(R.id.btnPaint).setOnClickListener { canvas.brushMode = FreeCanvasView.MaskBrush.PAINT }
        findViewById<View>(R.id.btnClearMask).setOnClickListener {
            canvas.clearMaskEdit()
            refreshRefineUI(false)
            refreshPropertyPanel()
        }
        findViewById<View>(R.id.btnDoneMask).setOnClickListener {
            canvas.exitMaskEdit()
            refreshRefineUI(false)
            refreshPropertyPanel()
        }
        val seekBrush = findViewById<SeekBar>(R.id.seekBrush)
        seekBrush.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                canvas.brushRadius = p.toFloat().coerceAtLeast(4f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        canvas.brushRadius = seekBrush.progress.toFloat().coerceAtLeast(4f)

        val seekOpacity = findViewById<SeekBar>(R.id.seekOpacity)
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { canvas.setElementAlpha(p / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val toggleLock = findViewById<ToggleButton>(R.id.toggleLock)
        toggleLock.setOnCheckedChangeListener { _, isChecked ->
            canvas.selected?.let { it.locked = isChecked; canvas.invalidate() }
        }

        findViewById<View>(R.id.btnAlignLeft).setOnClickListener { canvas.alignLeft() }
        findViewById<View>(R.id.btnAlignCenterH).setOnClickListener { canvas.alignCenterH() }
        findViewById<View>(R.id.btnAlignRight).setOnClickListener { canvas.alignRight() }
        findViewById<View>(R.id.btnAlignTop).setOnClickListener { canvas.alignTop() }
        findViewById<View>(R.id.btnAlignCenterV).setOnClickListener { canvas.alignCenterV() }
        findViewById<View>(R.id.btnAlignBottom).setOnClickListener { canvas.alignBottom() }
        findViewById<View>(R.id.btnDistributeH).setOnClickListener { canvas.distributeH() }
        findViewById<View>(R.id.btnDistributeV).setOnClickListener { canvas.distributeV() }
        findViewById<View>(R.id.btnLayerFront).setOnClickListener { canvas.bringToFrontLayer() }
        findViewById<View>(R.id.btnLayerBack).setOnClickListener { canvas.sendToBack() }
        findViewById<View>(R.id.btnLayerUp).setOnClickListener { canvas.moveLayerUp() }
        findViewById<View>(R.id.btnLayerDown).setOnClickListener { canvas.moveLayerDown() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { canvas.deleteSelected(); refreshPropertyPanel() }
    }

    private fun bindBoardPanel() {
        val metrics = resources.displayMetrics
        val dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, metrics)

        // 背景色色块
        val bgColors = listOf(
            0xFFFFFFFF.toInt(), 0xFFF5F5F5.toInt(), 0xFFECEFF1.toInt(), 0xFFCFD8DC.toInt(), 0xFF9E9E9E.toInt(), 0xFF616161.toInt(), 0xFF212121.toInt(), 0xFF000000.toInt(),
            0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(), 0xFF1E88E5.toInt(), 0xFF00ACC1.toInt(), 0xFF00897B.toInt(), 0xFF43A047.toInt(),
            0xFFFDD835.toInt(), 0xFFFFB300.toInt(), 0xFFFB8C00.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFF90A4AE.toInt()
        )
        val bgRow = findViewById<LinearLayout>(R.id.bgColorRow)
        val swatch = (20 * dip).toInt()
        bgColors.forEach { color ->
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((1.5f * dip).toInt(), 0xFFCCCCCC.toInt())
            }
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(swatch, swatch).apply {
                    marginEnd = (5 * dip).toInt()
                }
                background = drawable
                setOnClickListener { canvas.bgColor = color }
            }
            bgRow.addView(v)
        }

        // 自定义颜色按钮
        val customBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFAFAFA.toInt())
            setStroke((1.5f * dip).toInt(), 0xFF90A4AE.toInt())
        }
        val customView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(swatch, swatch).apply {
                marginEnd = (5 * dip).toInt()
            }
            background = customBg
            setOnClickListener { showColorPicker() }
        }
        val plus = android.widget.TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(swatch, swatch)
            gravity = android.view.Gravity.CENTER
            text = "+"
            textSize = 16f
            setTextColor(0xFF37474F.toInt())
        }
        val customWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(swatch, swatch).apply {
                marginEnd = (5 * dip).toInt()
            }
            addView(customView)
            addView(plus)
            setOnClickListener { showColorPicker() }
        }
        bgRow.addView(customWrap)

        // 尺寸比例按钮（null 表示"屏幕分辨率"选项）
        val sizes = listOf<Pair<String, Pair<Float, Float>?>>(
            "size_screen" to null,
            "size_square" to (1f to 1f),
            "size_4_3" to (4f to 3f),
            "size_3_4" to (3f to 4f),
            "size_16_9" to (16f to 9f),
            "size_9_16" to (9f to 16f)
        )
        val base = 1080f
        val sizeRow = findViewById<LinearLayout>(R.id.sizeRow)
        sizes.forEach { (nameRes, ratio) ->
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (44 * dip).toInt(),
                    (30 * dip).toInt()
                ).apply { marginEnd = (4 * dip).toInt() }
                text = getString(resources.getIdentifier(nameRes, "string", packageName))
                textSize = 10f
                val action: () -> Unit = if (ratio == null) {
                    { canvas.useScreenResolution() }
                } else {
                    val (rw, rh) = ratio
                    val w = base * rw
                    val h = base * rh
                    { canvas.setCanvasSize(w, h) }
                }
                setOnClickListener { action() }
            }
            sizeRow.addView(btn)
        }
    }

    /** 自定义背景色调色板：RGB 三滑动条 + 实时预览 */
    private fun showColorPicker() {
        val metrics = resources.displayMetrics
        val dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, metrics)

        // 当前颜色（默认白）
        var cur = canvas.bgColor

        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * dip).toInt()
            )
            background = GradientDrawable().apply {
                setColor(cur)
                setStroke((1f * dip).toInt(), 0xFFCCCCCC.toInt())
            }
        }

        fun makeSeek(initial: Int, onChanged: (Int) -> Unit): SeekBar {
            return SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                max = 255
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        onChanged(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
        }

        val rSeek = makeSeek(Color.red(cur)) { r -> cur = Color.argb(255, r, Color.green(cur), Color.blue(cur)); preview.background = GradientDrawable().apply { setColor(cur); setStroke((1f * dip).toInt(), 0xFFCCCCCC.toInt()) } }
        val gSeek = makeSeek(Color.green(cur)) { g -> cur = Color.argb(255, Color.red(cur), g, Color.blue(cur)); preview.background = GradientDrawable().apply { setColor(cur); setStroke((1f * dip).toInt(), 0xFFCCCCCC.toInt()) } }
        val bSeek = makeSeek(Color.blue(cur)) { b -> cur = Color.argb(255, Color.red(cur), Color.green(cur), b); preview.background = GradientDrawable().apply { setColor(cur); setStroke((1f * dip).toInt(), 0xFFCCCCCC.toInt()) } }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dip).toInt(), (16 * dip).toInt(), (16 * dip).toInt(), (16 * dip).toInt())
            addView(preview)
            addView(TextView(this@MainActivity).apply { text = "R"; textSize = 12f; setTextColor(0xFF616161.toInt()); setPadding(0, (8 * dip).toInt(), 0, 0) })
            addView(rSeek)
            addView(TextView(this@MainActivity).apply { text = "G"; textSize = 12f; setTextColor(0xFF616161.toInt()) })
            addView(gSeek)
            addView(TextView(this@MainActivity).apply { text = "B"; textSize = 12f; setTextColor(0xFF616161.toInt()) })
            addView(bSeek)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.custom_color)
            .setView(panel)
            .setPositiveButton(android.R.string.ok) { _, _ -> canvas.bgColor = cur }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshPropertyPanel() {
        val el = canvas.selected
        if (el == null) {
            propContainer.visibility = View.GONE
            tvPanelTitle.visibility = View.GONE
            tvNoSelection.visibility = View.GONE
            return
        }
        propContainer.visibility = View.VISIBLE
        tvPanelTitle.visibility = View.VISIBLE
        tvNoSelection.visibility = View.GONE
        val seekOpacity = findViewById<SeekBar>(R.id.seekOpacity)
        seekOpacity.progress = (el.alpha * 100).toInt()
        val toggleLock = findViewById<ToggleButton>(R.id.toggleLock)
        toggleLock.isChecked = el.locked
        // 文本元素不支持裁剪/滤镜/蒙版/替换
        val isImg = el is ImageElement
        listOf(R.id.btnReplace, R.id.btnCrop, R.id.btnFilter, R.id.btnMask).forEach {
            findViewById<View>(it).visibility = if (isImg) View.VISIBLE else View.GONE
        }
        // 手动修正按钮：仅对去除背景(已抠图)的图片可用
        val canRefine = isImg && (el as? ImageElement)?.segmented == true
        findViewById<View>(R.id.btnRefine).visibility = if (canRefine) View.VISIBLE else View.GONE
        // 未进入修正模式时，工具条/笔刷条隐藏
        val inEdit = (el as? ImageElement)?.inMaskEdit == true
        if (!inEdit) {
            findViewById<View>(R.id.refineToolbar).visibility = View.GONE
            findViewById<View>(R.id.brushSizeRow).visibility = View.GONE
        }
        // 自由模式下刷新左侧图片列表
        if (mode == "free") buildFreeAssets()
    }

    /** 切换手动修正工具条/笔刷条的可见性 */
    private fun refreshRefineUI(editing: Boolean) {
        findViewById<View>(R.id.refineToolbar).visibility = if (editing) View.VISIBLE else View.GONE
        findViewById<View>(R.id.brushSizeRow).visibility = if (editing) View.VISIBLE else View.GONE
        // 修正模式下，隐藏其他图片操作按钮避免误触
        val editVis = if (editing) View.GONE else View.VISIBLE
        listOf(R.id.btnReplace, R.id.btnCrop, R.id.btnFilter, R.id.btnMask, R.id.btnSegment, R.id.btnRefine)
            .forEach { findViewById<View>(it).visibility = editVis }
    }

    // ---------- 添加图片 ----------
    private fun addImage() {
        if (mode == "grid") {
            pickForGrid.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            return
        }
        // 自由/海报/拼图模式：支持一次多选多张图片
        pickMultiple.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun handlePickedMultiple(uris: List<Uri>) {
        when (mode) {
            "grid" -> setupGrid(uris)
            "poster", "puzzle" -> uris.forEach { uri ->
                val bmp = canvas.loadBitmapFromUri(uri) ?: return@forEach
                addImageToTemplate(uri, bmp)
            }
            else -> uris.forEach { uri ->
                val bmp = canvas.loadBitmapFromUri(uri) ?: return@forEach
                canvas.addImage(uri, bmp)
            }
        }
    }

    private fun handlePicked(uri: Uri, forGrid: Boolean) {
        val bmp = canvas.loadBitmapFromUri(uri)
        if (bmp != null) {
            when (mode) {
                "grid" -> setupGrid(listOf(uri))
                "poster", "puzzle" -> addImageToTemplate(uri, bmp)
                else -> canvas.addImage(uri, bmp)
            }
        }
    }

    // ---------- 网格模式 ----------
    private fun setupGrid(uris: List<Uri>) {
        curUris = uris
        val adapter = object : BaseAdapter() {
            override fun getCount() = uris.size
            override fun getItem(i: Int) = uris[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                val iv = (cv as? ImageView) ?: ImageView(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                iv.setImageURI(uris[i])
                return iv
            }
        }
        gridView.adapter = adapter
        gridView.visibility = View.VISIBLE
    }

    private fun mergeGrid(): Bitmap {
        val n = curUris.size
        val cols = ceil(sqrt(n.toFloat())).toInt()
        val rows = ceil(n.toFloat() / cols).toInt()
        val cell = 1080 / cols
        val bmp = Bitmap.createBitmap(1080, cell * rows, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        curUris.forEachIndexed { i, uri ->
            val b = canvas.loadBitmapFromUri(uri) ?: return@forEachIndexed
            val col = i % cols; val row = i / cols
            val dst = Rect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell)
            c.drawBitmap(b, null, dst, null)
        }
        return bmp
    }

    // ---------- 阶段5 模板应用 ----------
    private var templateSlots: List<RectF> = emptyList()

    private fun applyPosterTemplate(name: String) {
        canvas.elements.clear()
        canvas.bgColor = Color.parseColor("#1A1A2E")
        templateSlots = when (name) {
            getString(R.string.template_5) -> listOf(RectF(90f, 120f, 990f, 720f))
            else -> listOf(RectF(140f, 140f, 940f, 640f))
        }
        val text = CanvasElement.TextElement(
            text = getString(R.string.poster_placeholder),
            textSizeSp = 56f, color = Color.WHITE,
            x = 120f, y = 820f, w = 840f, h = 120f, rotation = 0f
        )
        text.zOrder = 100
        canvas.elements.add(text)
        canvas.selectElement(null)
        canvas.invalidate()
        Toast.makeText(this, "已应用$name，点击下方添加图片填充槽位", Toast.LENGTH_SHORT).show()
    }

    private fun applyPuzzleTemplate(name: String) {
        canvas.elements.clear()
        canvas.bgColor = Color.WHITE
        templateSlots = when (name) {
            getString(R.string.template_1) -> listOf( // 经典 2x2
                RectF(40f, 40f, 530f, 530f), RectF(550f, 40f, 1040f, 530f),
                RectF(40f, 550f, 530f, 1040f), RectF(550f, 550f, 1040f, 1040f))
            getString(R.string.template_2) -> listOf( // 杂志 左大右两小
                RectF(40f, 40f, 700f, 1040f), RectF(720f, 40f, 1040f, 520f),
                RectF(720f, 560f, 1040f, 1040f))
            getString(R.string.template_3) -> listOf( // 卡片 上大下小
                RectF(120f, 60f, 960f, 560f), RectF(120f, 600f, 480f, 1000f),
                RectF(600f, 600f, 960f, 1000f))
            else -> listOf( // 三联 水平
                RectF(40f, 120f, 360f, 960f), RectF(380f, 120f, 700f, 960f),
                RectF(720f, 120f, 1040f, 960f))
        }
        canvas.selectElement(null)
        canvas.invalidate()
        Toast.makeText(this, "已选择$name，点击下方添加图片填充槽位", Toast.LENGTH_SHORT).show()
    }

    /** 把图片放入模板下一个空槽位（阶段5 海报/拼图） */
    private fun addImageToTemplate(uri: Uri, bmp: Bitmap) {
        val slot = templateSlots.firstOrNull { s ->
            canvas.elements.none { it is ImageElement && it.x == s.left && it.y == s.top }
        } ?: templateSlots.firstOrNull()
        if (slot == null) { canvas.addImage(uri, bmp); return }
        val aspect = bmp.width.toFloat() / bmp.height
        val slotAspect = slot.width() / slot.height()
        val ww: Float; val hh: Float
        if (slotAspect > aspect) { hh = slot.height(); ww = hh * aspect }
        else { ww = slot.width(); hh = ww / aspect }
        val x = slot.left + (slot.width() - ww) / 2f
        val y = slot.top + (slot.height() - hh) / 2f
        val el = ImageElement(uri, bmp, x, y, ww, hh, 0f)
        el.zOrder = (canvas.elements.maxOfOrNull { it.zOrder } ?: 0) + 1
        canvas.elements.add(el)
        canvas.selectElement(el)
        canvas.invalidate()
    }

    // ---------- 阶段4：裁剪/滤镜/蒙版入口 ----------
    private fun openCrop() {
        val el = canvas.selected as? ImageElement ?: return
        val view = layoutInflater.inflate(R.layout.dialog_crop, null)
        val cropView = view.findViewById<CropOverlayView>(R.id.cropView)
        cropView.srcBitmap = el.bitmap
        val dlg = AlertDialog.Builder(this).setTitle(R.string.crop).setView(view).create()
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dlg.dismiss() }
        view.findViewById<View>(R.id.btnOk).setOnClickListener {
            val cropped = cropView.resultBitmap()
            if (cropped != null) {
                val idx = canvas.elements.indexOf(el)
                if (idx >= 0) {
                    canvas.elements[idx] = el.copy(bitmap = cropped,
                        w = el.w, h = el.w / (cropped.width.toFloat() / cropped.height))
                }
                canvas.selectElement(canvas.elements[idx])
                canvas.invalidate()
            }
            dlg.dismiss()
        }
        dlg.show()
    }

    private fun openFilter() {
        val el = canvas.selected as? ImageElement ?: return
        val names = ImageFilter.values().map { it.name }
        AlertDialog.Builder(this).setTitle(R.string.filter)
            .setItems(names.toTypedArray()) { _, i ->
                (canvas.selected as? ImageElement)?.filter = ImageFilter.values()[i]
                canvas.invalidate()
            }.show()
    }

    private fun openMask() {
        val el = canvas.selected as? ImageElement ?: return
        val names = MaskShape.values().map { it.name }
        AlertDialog.Builder(this).setTitle(R.string.mask)
            .setItems(names.toTypedArray()) { _, i ->
                (canvas.selected as? ImageElement)?.mask = MaskShape.values()[i]
                canvas.invalidate()
            }.show()
    }

    /** 抠人像：对当前选中图片做分割，得到透明背景结果 */
    private fun openSegment() {
        val el = canvas.selected as? ImageElement ?: return
        Toast.makeText(this, R.string.segmenting, Toast.LENGTH_SHORT).show()
        Thread {
            val src = el.bitmap
            val result = Segmenter.segment(this, src)
            runOnUiThread {
                if (result == null) {
                    val msg = Segmenter.lastError?.lineSequence()?.firstOrNull()?.take(120)
                        ?: getString(R.string.segment_fail)
                    Toast.makeText(this, getString(R.string.segment_fail) + ": " + msg, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // 替换元素 bitmap 并保持尺寸比例
                val ratio = result.width.toFloat() / result.height
                el.bitmap = result
                el.segmented = true
                el.w = el.w   // 保持当前宽，高度按新比例重算
                el.h = el.w / ratio
                canvas.invalidate()
                Toast.makeText(this, R.string.segment_ok, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** 一键抠图：对画布上所有图片元素逐张抠人像 */
    private fun segmentAllImages() {
        val images = canvas.elements.filterIsInstance<ImageElement>()
        if (images.isEmpty()) {
            Toast.makeText(this, R.string.err_no_images, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.segmenting, Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            var fail = 0
            for (el in images) {
                val r = Segmenter.segment(this, el.bitmap)
                if (r != null) {
                    val ratio = r.width.toFloat() / r.height
                    el.bitmap = r
                    el.segmented = true
                    el.h = el.w / ratio
                    ok++
                } else {
                    fail++
                }
            }
            runOnUiThread {
                canvas.invalidate()
                val msg = getString(R.string.segment_all_done, ok, fail)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ---------- 合成 / 保存 / 分享 ----------
    private fun mergeAndShare() {
        val bmp = if (mode == "grid" && curUris.isNotEmpty()) mergeGrid() else canvas.renderToBitmap()
        val uri = saveToGallery(bmp)
        if (uri != null) shareBitmap(uri)
    }

    private fun saveToGallery(bmp: Bitmap): Uri? {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "collage_$time.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = contentResolver
            val coll = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val v = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Collage")
            }
            val uri = cr.insert(coll, v) ?: return null
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            uri
        } else {
            val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Collage")
            dir.mkdirs()
            val f = File(dir, displayName)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Uri.fromFile(f)
        }
    }

    private fun shareBitmap(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享拼图"))
    }
}
