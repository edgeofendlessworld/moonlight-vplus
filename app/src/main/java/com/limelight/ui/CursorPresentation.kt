package com.limelight.ui

import android.graphics.Bitmap
import android.view.View

/**
 * 光标呈现层的统一接口。
 *
 * 目前有两种实现：
 *  - [CursorView]：普通 View，跟随 Activity 窗口一起重绘，与视频 SurfaceView 分属两条合成路径。
 *  - [CursorSurfaceOverlay]：独立 SurfaceView（media overlay 层），由 SurfaceFlinger 与视频同一
 *    合成事务呈现，配合独立渲染线程与最新值合帧可显著降低指针跟随延迟。
 *
 * 上层（CursorServiceManager / LocalCursorRenderer / PanZoomHandler）只依赖本接口，
 * 便于在不改动调用链的前提下切换呈现方式。
 */
interface CursorPresentation {
    /** 对应的 View，供布局变换（缩放 / 平移）使用。 */
    val presentationView: View

    /**
     * 位置更新是否必须切回主线程。
     *
     * [CursorView] 是普通 View，只能主线程 `invalidate()`；[CursorSurfaceOverlay] 自带渲染线程
     * 与合帧，直接调用即可，多绕一次主线程等于白等一帧。
     */
    val requiresMainThreadUpdates: Boolean get() = true

    /** 设置主机下发的光标位图与热点；bitmap 为 null 时回退内置箭头。 */
    fun setCursorBitmap(bitmap: Bitmap?, hotX: Int, hotY: Int)

    /** 丢弃主机位图，回退到内置箭头。 */
    fun resetToDefault()

    /** 更新指针坐标（View 坐标系，单位像素）。 */
    fun updateCursorPosition(x: Float, y: Float)

    /** 输入侧希望显示指针。 */
    fun show()

    /** 输入侧希望隐藏指针。 */
    fun hide()

    /** 主机侧的光标可见性（来自光标通道更新）。 */
    fun setHostCursorVisible(visible: Boolean)
}
