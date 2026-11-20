package com.limelight.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.View
import androidx.core.content.ContextCompat
import com.limelight.R

/**
 * 低延迟光标图层。
 *
 * 与 [CursorView] 的关键差异：本类是 **SurfaceView**，光标被绘制到独立于 Activity 窗口的
 * Surface 上，并通过 `setZOrderMediaOverlay(true)` 与视频 Surface 处于同一合成栈，
 * 由 SurfaceFlinger 在同一次合成事务里叠加。这样避开两层额外延迟：
 *
 *  1. [CursorView] 的 `invalidate()` 会让整个窗口重绘并重新提交窗口 buffer，
 *     光标位置要等主线程走完 measure/layout/draw，再等窗口 buffer 入队；
 *  2. 本类只向自己的一个小 Surface 提交，且绘制线程独立于主线程。
 *
 * 位置更新采用「最新值覆盖」合并：连续到达的多个指针事件只会在渲染线程上产生一次绘制，
 * 并且永远使用最后到达的坐标，因此不会在主线程或渲染线程排队堆积。
 *
 * 注意：本类不参与系统指针（`PointerIcon`）体系，只用于客户端自绘光标，
 * 需要配合指针捕获（相对位移）或触控板相对位移使用。
 */
class CursorSurfaceOverlay(context: Context) : SurfaceView(context), SurfaceHolder.Callback,
    CursorPresentation {

    /** 供布局变换（[com.limelight.utils.PanZoomHandler] 的 pivot/scale/x/y）使用。 */
    override val presentationView: View get() = this

    /** 自带渲染线程与合帧，调用方无需再切主线程。 */
    override val requiresMainThreadUpdates: Boolean get() = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    /** 同时保护下面的状态字段与 drawPending。 */
    private val stateLock = Any()

    /** 主机下发的光标位图；null 时回退内置箭头。 */
    private var cursorBitmap: Bitmap? = null
    private var pivotX = 0f
    private var pivotY = 0f
    private var posX = -100f
    private var posY = -100f
    private var inputWantsCursor = false
    private var hostCursorVisible = false

    /** 已有一个待执行的绘制：用于把同一帧内的多次位置更新合并成一次。 */
    private var drawPending = false

    @Volatile
    private var surfaceReady = false

    @Volatile
    private var destroyed = false

    private val defaultCursorBitmap: Bitmap?
    private val defaultPivotX: Float
    private val defaultPivotY: Float

    private val drawRunnable = Runnable { performDraw() }

    init {
        // 叠在视频 Surface 之上，但仍在应用窗口之下：不影响其它 UI 的层级与输入。
        setZOrderMediaOverlay(true)
        setWillNotDraw(true)
        // 必须半透明，否则这块 Surface 会盖住下面的视频（默认是不透明的 RGBX）。
        holder.setFormat(PixelFormat.TRANSLUCENT)

        val drawable = ContextCompat.getDrawable(context, R.drawable.arrow)
        if (drawable != null) {
            val bmp = Bitmap.createBitmap(DEFAULT_SIZE, DEFAULT_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, DEFAULT_SIZE, DEFAULT_SIZE)
            drawable.draw(canvas)
            defaultCursorBitmap = bmp
            defaultPivotX = DEFAULT_SIZE * (6f / 24f)
            defaultPivotY = DEFAULT_SIZE * (3f / 24f)
        } else {
            defaultCursorBitmap = null
            defaultPivotX = 0f
            defaultPivotY = 0f
        }

        holder.addCallback(this)
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        synchronized(stateLock) { drawPending = false }
        ensureRenderThread()
        requestDraw()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestDraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 不能在这里 lockCanvas：Surface 已失效。只需放弃排队中的绘制，
        // 等下一次 surfaceCreated 重新开始。
        surfaceReady = false
        synchronized(stateLock) { drawPending = false }
    }

    // ---- CursorPresentation ----

    override fun setCursorBitmap(bitmap: Bitmap?, hotX: Int, hotY: Int) {
        synchronized(stateLock) {
            cursorBitmap = bitmap
            pivotX = hotX.toFloat()
            pivotY = hotY.toFloat()
        }
        requestDraw()
    }

    override fun resetToDefault() {
        synchronized(stateLock) { cursorBitmap = null }
        requestDraw()
    }

    override fun updateCursorPosition(x: Float, y: Float) {
        synchronized(stateLock) {
            posX = x
            posY = y
        }
        requestDraw()
    }

    override fun show() {
        synchronized(stateLock) { inputWantsCursor = true }
        requestDraw()
    }

    override fun hide() {
        synchronized(stateLock) { inputWantsCursor = false }
        requestDraw()
    }

    override fun setHostCursorVisible(visible: Boolean) {
        synchronized(stateLock) { hostCursorVisible = visible }
        requestDraw()
    }

    /**
     * 释放渲染线程。调用后本实例不可再用。
     */
    fun destroy() {
        destroyed = true
        synchronized(stateLock) { drawPending = false }
        // 先摘掉回调再做别的：destroyed=true 之后 surfaceCreated 会被 ensureRenderThread()
        // 直接挡掉，Surface 会永久留一块空白透明层盖在视频上。
        holder.removeCallback(this)
        renderHandler?.removeCallbacksAndMessages(null)
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
        (parent as? ViewGroup)?.removeView(this)
    }

    // ---- 内部实现 ----

    private fun isVisibleLocked(): Boolean = inputWantsCursor && hostCursorVisible

    private fun ensureRenderThread() {
        if (renderThread != null || destroyed) return
        val thread = HandlerThread("CursorSurfaceRenderer").apply { start() }
        renderThread = thread
        renderHandler = Handler(thread.looper)
    }

    private fun requestDraw() {
        if (destroyed) return
        val handler = renderHandler ?: return
        synchronized(stateLock) {
            if (drawPending) return
            drawPending = true
        }
        handler.post(drawRunnable)
    }

    private fun performDraw() {
        synchronized(stateLock) {
            drawPending = false
            if (destroyed) return
        }
        if (!surfaceReady) return

        // 快照当前状态：只在锁内读一次，绘制期间不再持锁。
        var bmp: Bitmap? = null
        var pX = 0f
        var pY = 0f
        var x = 0f
        var y = 0f
        var visible = false
        synchronized(stateLock) {
            visible = isVisibleLocked()
            val custom = cursorBitmap
            if (custom != null) {
                bmp = custom
                pX = pivotX
                pY = pivotY
            } else {
                bmp = defaultCursorBitmap
                pX = defaultPivotX
                pY = defaultPivotY
            }
            x = posX
            y = posY
        }

        val canvas = try {
            holder.lockCanvas() ?: return
        } catch (_: Exception) {
            return
        }

        try {
            // Surface 是持久的，必须整块清空，否则上一帧的箭头会残留。
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (visible && bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, x - pX, y - pY, paint)
            }
        } catch (_: Exception) {
            // 位图或 Canvas 在绘制期间失效，忽略这一帧。
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
                // Surface 在绘制过程中被销毁；交由 surfaceCreated 恢复。
            }
        }
    }

    companion object {
        private const val DEFAULT_SIZE = 24
    }
}
