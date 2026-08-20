package com.example.collage

import android.graphics.Bitmap
import android.net.Uri

/**
 * 画布上的一个元素（图片或文本）。所有坐标使用画布逻辑坐标（0..logicalSize）。
 */
sealed class CanvasElement(val type: Type) {
    enum class Type { IMAGE, TEXT }

    abstract var x: Float
    abstract var y: Float
    abstract var w: Float
    abstract var h: Float
    abstract var rotation: Float

    // 阶段2 新增属性
    var alpha: Float = 1f          // 不透明度 0..1
    var locked: Boolean = false    // 锁定后不可触摸操作
    var zOrder: Int = 0            // 图层顺序，越大越靠上

    /** 该元素当前内容的宽高比，用于等比缩放 */
    abstract fun aspect(): Float

    /** 返回元素在画布逻辑坐标系中的包围矩形（未旋转） */
    fun bounds() = android.graphics.RectF(x, y, x + w, y + h)

    /** 判断逻辑坐标点 (px,py) 是否落在元素内（忽略旋转近似） */
    fun contains(px: Float, py: Float): Boolean {
        val r = bounds()
        return px in r.left..r.right && py in r.top..r.bottom
    }

    data class ImageElement(
        var uri: Uri,
        var bitmap: Bitmap,
        override var x: Float,
        override var y: Float,
        override var w: Float,
        override var h: Float,
        override var rotation: Float = 0f,
        // 阶段4 新增
        var filter: ImageFilter = ImageFilter.NONE,
        var mask: MaskShape = MaskShape.NONE,
        // 手动修正蒙版：与 bitmap 同尺寸的 A8 单通道图。
        // 像素值 0=完全擦除(透明)，255=完全保留(不透明)，中间=半透明混合。
        // null 表示未手动修正（使用 AI 原始 alpha）。
        var userMask: Bitmap? = null,
        // 是否处于手动修正（橡皮擦/画笔）编辑模式
        var inMaskEdit: Boolean = false,
        // 是否已通过 AI 抠图（去除背景），决定能否"手动修正"
        var segmented: Boolean = false
    ) : CanvasElement(Type.IMAGE) {
        override fun aspect(): Float = if (bitmap.height != 0) bitmap.width.toFloat() / bitmap.height else 1f
        fun copy(
            uri: Uri = this.uri,
            bitmap: Bitmap = this.bitmap,
            x: Float = this.x,
            y: Float = this.y,
            w: Float = this.w,
            h: Float = this.h,
            rotation: Float = this.rotation,
            alpha: Float = this.alpha,
            locked: Boolean = this.locked,
            zOrder: Int = this.zOrder,
            filter: ImageFilter = this.filter,
            mask: MaskShape = this.mask,
            userMask: Bitmap? = this.userMask,
            inMaskEdit: Boolean = this.inMaskEdit,
            segmented: Boolean = this.segmented
        ) = ImageElement(uri, bitmap, x, y, w, h, rotation, filter, mask, userMask, inMaskEdit, segmented).also {
            it.alpha = alpha; it.locked = locked; it.zOrder = zOrder
        }
    }

    data class TextElement(
        var text: String,
        var textSizeSp: Float,
        var color: Int,
        override var x: Float,
        override var y: Float,
        override var w: Float,
        override var h: Float,
        override var rotation: Float = 0f
    ) : CanvasElement(Type.TEXT) {
        override fun aspect(): Float = w / h
    }
}
