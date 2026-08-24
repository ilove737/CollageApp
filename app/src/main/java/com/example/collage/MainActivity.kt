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
import android.view.Gravity
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.collage.CanvasElement.ImageElement
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
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

    /** 画板设置 BottomSheet（懒加载复用） */
    private var boardSheet: BottomSheetDialog? = null

    /** 是否有抠图任务进行中（防重复点击） */
    private var segmenting = false

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

        // 顶栏
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { mergeAndShare() }

        // 底部全局工具行
        findViewById<View>(R.id.btnAdd).setOnClickListener { addImage() }
        findViewById<View>(R.id.btnRandom).setOnClickListener { canvas.randomizeLayout() }
        findViewById<View>(R.id.btnSegmentAll).setOnClickListener { segmentAllImages() }
        findViewById<View>(R.id.btnBoard).setOnClickListener { showBoardSheet() }
        val seekZoom = findViewById<Slider>(R.id.seekZoom)
        seekZoom.addOnChangeListener { _, value, _ ->
            canvas.canvasScale = (value / 100f).coerceIn(0.3f, 3f)
            canvas.invalidate()
        }

        // 右侧属性
        bindPropertyPanel()

        // 属性面板 Tab 切换（操作 / 排列 / 属性）
        val tabProps = findViewById<TabLayout>(R.id.tabProps)
        val flipperProps = findViewById<ViewFlipper>(R.id.flipperProps)
        tabProps.addTab(tabProps.newTab().setText(R.string.tab_actions))
        tabProps.addTab(tabProps.newTab().setText(R.string.tab_arrange))
        tabProps.addTab(tabProps.newTab().setText(R.string.tab_props))
        tabProps.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                flipperProps.displayedChild = tab.position
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // 左侧模式导航（动态构建，避免 RecyclerView 依赖）
        buildModeList()

        selectMode("free")
        refreshPropertyPanel()
    }

    private fun buildModeList() {
        modeList.removeAllViews()
        for (m in modes) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_mode, modeList, false)
            val icon = v.findViewById<ImageView>(R.id.modeIcon)
            val label = v.findViewById<TextView>(R.id.modeLabel)
            icon.setImageResource(m.icon)
            label.setText(m.label)
            applyModeItemState(v, m.id == mode)
            v.setOnClickListener {
                mode = m.id
                for (i in 0 until modeList.childCount) {
                    modeList.getChildAt(i).setBackgroundResource(R.drawable.bg_mode_tab)
                    val childIcon = modeList.getChildAt(i).findViewById<ImageView>(R.id.modeIcon)
                    val childLabel = modeList.getChildAt(i).findViewById<TextView>(R.id.modeLabel)
                    childIcon.setColorFilter(ContextCompat.getColor(this, R.color.onSurfaceVariant))
                    childLabel.setTextColor(ContextCompat.getColor(this, R.color.onSurfaceVariant))
                }
                applyModeItemState(v, true)
                selectMode(m.id)
            }
            modeList.addView(v)
        }
    }

    /** 模式项选中态：胶囊底 + 主色图标/文字 */
    private fun applyModeItemState(v: View, selected: Boolean) {
        v.setBackgroundResource(if (selected) R.drawable.bg_mode_tab_sel else R.drawable.bg_mode_tab)
        val colorRes = if (selected) R.color.primary else R.color.onSurfaceVariant
        v.findViewById<ImageView>(R.id.modeIcon).setColorFilter(ContextCompat.getColor(this, colorRes))
        v.findViewById<TextView>(R.id.modeLabel).setTextColor(ContextCompat.getColor(this, colorRes))
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
        val title = TextView(this).apply {
            setText(R.string.images); setTextSize(12f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurfaceVariant))
        }
        leftListContainer.addView(title)
        if (canvas.elements.isEmpty()) {
            val empty = TextView(this).apply {
                setText("点击下方“添加图片”"); setTextSize(12f)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurfaceVariant))
            }
            leftListContainer.addView(empty)
            return
        }
        canvas.elements.sortedByDescending { it.zOrder }.forEachIndexed { idx, el ->
            val row = TextView(this).apply {
                text = if (el is ImageElement) "图片 ${canvas.elements.size - idx}" else "文本"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurface))
                setPadding(20, 24, 20, 24)
                setBackgroundResource(R.drawable.bg_list_item)
                setOnClickListener { canvas.selectElement(el); refreshPropertyPanel() }
            }
            leftListContainer.addView(row)
        }
    }

    // 左侧列表：网格模式 -> 模板缩略图（占位）
    private fun buildGridTemplates() {
        leftListContainer.removeAllViews()
        val title = TextView(this).apply {
            setText(R.string.templates); setTextSize(12f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurfaceVariant))
        }
        leftListContainer.addView(title)
        val tips = TextView(this).apply {
            setText(R.string.templates_hint); setTextSize(11f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurfaceVariant))
        }
        leftListContainer.addView(tips)
        val dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
        val btn = MaterialButton(this).apply {
            text = "选择图片(2-9张)"
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.secondaryContainer)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSecondaryContainer))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dip).toInt() }
            setOnClickListener { pickForGrid.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        leftListContainer.addView(btn)
    }

    // 阶段5 海报模板
    private fun buildPosterTemplates() {
        leftListContainer.removeAllViews()
        addTemplateSectionTitle()
        listOf(R.string.template_5, R.string.template_6).forEach { tid ->
            leftListContainer.addView(templateButton(tid) { applyPosterTemplate(getString(tid)) })
        }
    }

    // 阶段5 拼图模板
    private fun buildPuzzleTemplates() {
        leftListContainer.removeAllViews()
        addTemplateSectionTitle()
        listOf(R.string.template_1, R.string.template_2, R.string.template_3, R.string.template_4).forEach { tid ->
            leftListContainer.addView(templateButton(tid) { applyPuzzleTemplate(getString(tid)) })
        }
    }

    private fun addTemplateSectionTitle() {
        leftListContainer.addView(TextView(this).apply {
            setText(R.string.templates); setTextSize(12f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurfaceVariant))
        })
    }

    private fun templateButton(labelRes: Int, onClick: () -> Unit): View {
        val dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(labelRes)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dip).toInt() }
            setOnClickListener { onClick() }
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
        // 擦除/画笔分段切换（单选组）
        val brushToggle = findViewById<MaterialButtonToggleGroup>(R.id.brushToggle)
        brushToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            canvas.brushMode =
                if (checkedId == R.id.btnErase) FreeCanvasView.MaskBrush.ERASE else FreeCanvasView.MaskBrush.PAINT
        }
        brushToggle.check(R.id.btnErase)
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
        val seekBrush = findViewById<Slider>(R.id.seekBrush)
        seekBrush.addOnChangeListener { _, value, _ ->
            canvas.brushRadius = value.coerceAtLeast(4f)
        }
        canvas.brushRadius = seekBrush.value.coerceAtLeast(4f)

        val seekOpacity = findViewById<Slider>(R.id.seekOpacity)
        seekOpacity.addOnChangeListener { _, value, _ ->
            canvas.setElementAlpha(value / 100f)
        }

        val toggleLock = findViewById<MaterialSwitch>(R.id.toggleLock)
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

    /** 打开画板设置 BottomSheet（背景色 / 尺寸），视图懒加载复用 */
    private fun showBoardSheet() {
        val dialog = boardSheet ?: BottomSheetDialog(this).also { d ->
            val v = layoutInflater.inflate(R.layout.bottom_sheet_board, null)
            bindBoardPanel(v)
            d.setContentView(v)
            boardSheet = d
        }
        dialog.show()
    }

    private fun bindBoardPanel(sheet: View) {
        val metrics = resources.displayMetrics
        val dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, metrics)

        // 背景色色块
        val bgColors = listOf(
            0xFFFFFFFF.toInt(), 0xFFF5F5F5.toInt(), 0xFFECEFF1.toInt(), 0xFFCFD8DC.toInt(), 0xFF9E9E9E.toInt(), 0xFF616161.toInt(), 0xFF212121.toInt(), 0xFF000000.toInt(),
            0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(), 0xFF1E88E5.toInt(), 0xFF00ACC1.toInt(), 0xFF00897B.toInt(), 0xFF43A047.toInt(),
            0xFFFDD835.toInt(), 0xFFFFB300.toInt(), 0xFFFB8C00.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFF90A4AE.toInt()
        )
        val bgRow = sheet.findViewById<LinearLayout>(R.id.bgColorRow)
        val swatch = (28 * dip).toInt()
        bgColors.forEachIndexed { idx, color ->
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((1.5f * dip).toInt(), ContextCompat.getColor(this@MainActivity, R.color.outlineVariant))
            }
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(swatch, swatch).apply {
                    marginEnd = (8 * dip).toInt()
                }
                background = drawable
                setOnClickListener {
                    canvas.bgColor = color
                    markSelectedSwatch(bgRow, idx, dip)
                }
            }
            bgRow.addView(v)
        }

        // 自定义颜色按钮
        val customBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@MainActivity, R.color.surfaceContainerHighest))
            setStroke((1.5f * dip).toInt(), ContextCompat.getColor(this@MainActivity, R.color.outline))
        }
        val customView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(swatch, swatch).apply {
                marginEnd = (8 * dip).toInt()
            }
            background = customBg
            setOnClickListener { showColorPicker() }
        }
        val plus = android.widget.TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(swatch, swatch)
            gravity = android.view.Gravity.CENTER
            text = "+"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurface))
        }
        val customWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(swatch, swatch).apply {
                marginEnd = (8 * dip).toInt()
            }
            addView(customView)
            addView(plus)
            setOnClickListener { showColorPicker() }
        }
        bgRow.addView(customWrap)

        // 尺寸比例 Chip（null 表示"屏幕分辨率"选项）
        val sizes = listOf<Pair<String, Pair<Float, Float>?>>(
            "size_screen" to null,
            "size_square" to (1f to 1f),
            "size_4_3" to (4f to 3f),
            "size_3_4" to (3f to 4f),
            "size_16_9" to (16f to 9f),
            "size_9_16" to (9f to 16f)
        )
        val base = 1080f
        val sizeRow = sheet.findViewById<LinearLayout>(R.id.sizeRow)
        sizes.forEach { (nameRes, ratio) ->
            val chip = Chip(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dip).toInt() }
                text = getString(resources.getIdentifier(nameRes, "string", packageName))
                isCheckable = false
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
            sizeRow.addView(chip)
        }
    }

    /** 给点击的色块加主色描边（自定义颜色入口除外） */
    private fun markSelectedSwatch(row: LinearLayout, index: Int, dip: Float) {
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child is FrameLayout) continue
            if (i != index) continue
            child.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke((2.5f * dip).toInt(), ContextCompat.getColor(this@MainActivity, R.color.primary))
            }
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

        fun makeSeek(initial: Int, onChanged: (Int) -> Unit): Slider {
            return Slider(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                valueFrom = 0f
                valueTo = 255f
                stepSize = 1f
                value = initial.toFloat()
                addOnChangeListener { _, value, _ -> onChanged(value.toInt()) }
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
        val refineToolbar = findViewById<View>(R.id.refineToolbar)
        val brushSizeRow = findViewById<View>(R.id.brushSizeRow)
        if (el == null) {
            propContainer.visibility = View.GONE
            tvPanelTitle.visibility = View.GONE
            tvNoSelection.visibility = View.GONE
            refineToolbar.visibility = View.GONE
            brushSizeRow.visibility = View.GONE
            return
        }
        val inEdit = (el as? ImageElement)?.inMaskEdit == true
        // 三态互斥：修正态只显示精修条；选中态显示上下文面板；两者都叠加在常显全局行上
        propContainer.visibility = if (inEdit) View.GONE else View.VISIBLE
        tvPanelTitle.visibility = if (inEdit) View.GONE else View.VISIBLE
        tvNoSelection.visibility = View.GONE
        refineToolbar.visibility = if (inEdit) View.VISIBLE else View.GONE
        brushSizeRow.visibility = if (inEdit) View.VISIBLE else View.GONE

        if (!inEdit) {
            val seekOpacity = findViewById<Slider>(R.id.seekOpacity)
            seekOpacity.value = ((el.alpha * 100).toInt()).coerceIn(0, 100).toFloat()
            val toggleLock = findViewById<MaterialSwitch>(R.id.toggleLock)
            toggleLock.isChecked = el.locked
            // 文本元素不支持裁剪/滤镜/蒙版/替换
            val isImg = el is ImageElement
            listOf(R.id.btnReplace, R.id.btnCrop, R.id.btnFilter, R.id.btnMask).forEach {
                findViewById<View>(it).visibility = if (isImg) View.VISIBLE else View.GONE
            }
            // 手动修正按钮：仅对去除背景(已抠图)的图片可用
            val canRefine = isImg && (el as? ImageElement)?.segmented == true
            findViewById<View>(R.id.btnRefine).visibility = if (canRefine) View.VISIBLE else View.GONE
        }
        // 自由模式下刷新左侧图片列表
        if (mode == "free") buildFreeAssets()
    }

    /** 切换手动修正 UI（可见性统一由 refreshPropertyPanel 按三态计算） */
    private fun refreshRefineUI(editing: Boolean) {
        refreshPropertyPanel()
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

    /** 通用横向选项 BottomSheet（Chip 单行滚动） */
    private fun showOptionSheet(titleRes: Int, names: List<String>, current: Int, onPick: (Int) -> Unit) {
        val metrics = resources.displayMetrics
        val dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, metrics)
        val dlg = BottomSheetDialog(this)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * dip).toInt(), (20 * dip).toInt(), (16 * dip).toInt(), (24 * dip).toInt())
        }
        names.forEachIndexed { i, name ->
            row.addView(Chip(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dip).toInt() }
                text = name
                isCheckable = true
                isChecked = i == current
                setOnClickListener {
                    onPick(i)
                    dlg.dismiss()
                }
            })
        }
        scroll.addView(row)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                setText(titleRes)
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurface))
                setPadding((16 * dip).toInt(), (16 * dip).toInt(), 0, 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(scroll)
        }
        dlg.setContentView(panel)
        dlg.show()
    }

    private fun filterLabel(f: ImageFilter): String = when (f) {
        ImageFilter.NONE -> "原图"
        ImageFilter.BRIGHTNESS -> "亮度"
        ImageFilter.CONTRAST -> "对比度"
        ImageFilter.GRAYSCALE -> "黑白"
        ImageFilter.SEPIA -> "复古"
        ImageFilter.WARM -> "暖色"
        ImageFilter.COOL -> "冷色"
        ImageFilter.INVERT -> "反色"
    }

    private fun maskLabel(m: MaskShape): String = when (m) {
        MaskShape.NONE -> "无"
        MaskShape.CIRCLE -> "圆形"
        MaskShape.HEART -> "心形"
        MaskShape.STAR -> "星形"
        MaskShape.BUBBLE -> "气泡"
        MaskShape.ROUNDED_RECT -> "圆角"
    }

    private fun openFilter() {
        val el = canvas.selected as? ImageElement ?: return
        val filters = ImageFilter.values()
        showOptionSheet(R.string.filter, filters.map { filterLabel(it) }, filters.indexOf(el.filter)) { i ->
            (canvas.selected as? ImageElement)?.filter = filters[i]
            canvas.invalidate()
        }
    }

    private fun openMask() {
        val el = canvas.selected as? ImageElement ?: return
        val shapes = MaskShape.values()
        showOptionSheet(R.string.mask, shapes.map { maskLabel(it) }, shapes.indexOf(el.mask)) { i ->
            (canvas.selected as? ImageElement)?.mask = shapes[i]
            canvas.invalidate()
        }
    }

    /** 抠人像：对当前选中图片做分割，得到透明背景结果 */
    private fun openSegment() {
        if (segmenting) return
        val el = canvas.selected as? ImageElement ?: return
        segmenting = true
        val handle = showSegmentDialog(1)
        Thread {
            val result = try {
                Segmenter.segment(this, el.bitmap)
            } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                finishSegment(handle) {
                    if (result == null) {
                        val msg = Segmenter.lastError?.lineSequence()?.firstOrNull()?.take(120)
                            ?: getString(R.string.segment_fail)
                        Toast.makeText(this, getString(R.string.segment_fail) + ": " + msg, Toast.LENGTH_LONG).show()
                    } else {
                        // 替换元素 bitmap 并保持尺寸比例
                        val ratio = result.width.toFloat() / result.height
                        el.bitmap = result
                        el.segmented = true
                        el.h = el.w / ratio
                        canvas.invalidate()
                        Toast.makeText(this, R.string.segment_ok, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    /** 一键抠图：链式逐张推理，每张完成立即上屏，进度对话框实时推进 */
    private fun segmentAllImages() {
        if (segmenting) return
        val images = canvas.elements.filterIsInstance<ImageElement>()
        if (images.isEmpty()) {
            Toast.makeText(this, R.string.err_no_images, Toast.LENGTH_SHORT).show()
            return
        }
        segmenting = true
        val handle = showSegmentDialog(images.size)
        var ok = 0
        var fail = 0
        fun step(i: Int) {
            if (i >= images.size) {
                segmenting = false
                handle.dialog.dismiss()
                canvas.invalidate()
                Toast.makeText(this, getString(R.string.segment_all_done, ok, fail), Toast.LENGTH_LONG).show()
                return
            }
            val el = images[i]
            Thread {
                val result = try {
                    Segmenter.segment(this, el.bitmap)
                } catch (e: Exception) { null }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // 元素数据只在主线程写，避免与渲染并发
                    if (result != null) {
                        val ratio = result.width.toFloat() / result.height
                        el.bitmap = result
                        el.segmented = true
                        el.h = el.w / ratio
                        ok++
                        canvas.invalidate()   // 抠好一张立即显示一张
                    } else {
                        fail++
                    }
                    updateSegmentProgress(handle, i + 1, images.size)
                    step(i + 1)
                }
            }.start()
        }
        step(0)
    }

    /** 统一收尾：复位进行中标志、关闭等待框，再执行 [body]（须在 UI 线程调用） */
    private fun finishSegment(handle: SegmentProgress, body: () -> Unit) {
        segmenting = false
        handle.dialog.dismiss()
        body()
    }

    /** 抠图等待对话框句柄：批量时 bar 非空 */
    private class SegmentProgress(
        val dialog: AlertDialog,
        val bar: ProgressBar?,
        val text: TextView
    )

    /**
     * 显示抠图等待对话框（模态、不可取消——推理线程无法中断）。
     * total==1 用不确定进度圆圈；total>1 用水平进度条并显示 i/n 文案。
     */
    private fun showSegmentDialog(total: Int): SegmentProgress {
        val dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dip).toInt(), (20 * dip).toInt(), (24 * dip).toInt(), (4 * dip).toInt())
        }
        val bar: ProgressBar?
        val text = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.onSurface))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * dip).toInt() }
        }
        if (total > 1) {
            bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = total
                progress = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            text.text = getString(R.string.segment_progress, 0, total)
            row.addView(bar)
        } else {
            bar = null
            text.setText(R.string.segmenting)
            row.addView(ProgressBar(this))
        }
        row.addView(text)
        val dlg = AlertDialog.Builder(this)
            .setView(row)
            .setCancelable(false)
            .create()
        dlg.show()
        return SegmentProgress(dlg, bar, text)
    }

    /** 更新批量抠图进度显示（i/n）。 */
    private fun updateSegmentProgress(handle: SegmentProgress, done: Int, total: Int) {
        handle.bar?.progress = done
        handle.text.text = getString(R.string.segment_progress, done, total)
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
