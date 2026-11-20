package com.limelight.binding.input.touch

import android.os.Handler
import android.os.Looper
import com.limelight.ui.CursorPresentation

class LocalCursorRenderer(
    private var cursorView: CursorPresentation?,
    viewWidth: Int,
    viewHeight: Int
) {
    private var viewWidth = maxOf(1, viewWidth)
    private var viewHeight = maxOf(1, viewHeight)
    private var cursorX = this.viewWidth / 2f
    private var cursorY = this.viewHeight / 2f

    /** 当前是否已向呈现层请求显示，用于让 [show]/[hide] 幂等。 */
    private var shown = false
    private val uiHandler = Handler(Looper.getMainLooper())

    init {
        postPosition()
    }

    fun updateCursorPosition(deltaX: Float, deltaY: Float) {
        cursorX = (cursorX + deltaX).coerceIn(0f, (viewWidth - 1).toFloat())
        cursorY = (cursorY + deltaY).coerceIn(0f, (viewHeight - 1).toFloat())
        postPosition()
    }

    fun setViewDimensions(width: Int, height: Int) {
        val newWidth = maxOf(1, width)
        val newHeight = maxOf(1, height)

        cursorX = if (viewWidth > 1) {
            cursorX * (newWidth - 1) / (viewWidth - 1)
        } else {
            newWidth / 2f
        }
        cursorY = if (viewHeight > 1) {
            cursorY * (newHeight - 1) / (viewHeight - 1)
        } else {
            newHeight / 2f
        }

        viewWidth = newWidth
        viewHeight = newHeight
        cursorX = cursorX.coerceIn(0f, (viewWidth - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (viewHeight - 1).toFloat())
        postPosition()
    }

    /**
     * 显示光标。幂等：物理鼠标每个事件都会调一次，重复投递会白白占用主线程队列。
     */
    fun show() {
        if (shown) return
        val view = cursorView ?: return
        shown = true
        val reveal = Runnable {
            view.show()
            view.updateCursorPosition(cursorX, cursorY)
        }
        if (view.requiresMainThreadUpdates) uiHandler.post(reveal) else reveal.run()
    }

    fun hide() {
        if (!shown) return
        shown = false
        val view = cursorView ?: return
        if (view.requiresMainThreadUpdates) uiHandler.post { view.hide() } else view.hide()
    }

    fun destroy() {
        val view = cursorView
        cursorView = null
        shown = false
        if (view == null) return
        if (view.requiresMainThreadUpdates) uiHandler.post { view.hide() } else view.hide()
    }

    fun getCursorAbsolutePosition(): FloatArray = floatArrayOf(cursorX, cursorY)

    private fun postPosition() {
        val view = cursorView ?: return
        if (view.requiresMainThreadUpdates) {
            uiHandler.post { view.updateCursorPosition(cursorX, cursorY) }
        } else {
            // 视频层光标自带渲染线程：直接投递，省掉一次主线程往返。
            view.updateCursorPosition(cursorX, cursorY)
        }
    }
}
