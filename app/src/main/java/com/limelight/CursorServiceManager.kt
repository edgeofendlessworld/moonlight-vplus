package com.limelight

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.PointerIcon
import android.widget.FrameLayout
import com.limelight.binding.input.touch.LocalCursorRenderer
import com.limelight.binding.input.touch.RelativeTouchContext
import com.limelight.binding.input.touch.TouchContext
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.CursorPresentation
import com.limelight.ui.CursorSurfaceOverlay
import com.limelight.ui.StreamView
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Owns cursor mode negotiation and presentation for an active stream.
 *
 * Cursor transport and packet reassembly live in moonlight-common-c. This class
 * deliberately only handles Android policy, decoding, caching, and rendering.
 */
class CursorServiceManager(
    private val streamView: StreamView,
    private val cursorPresentation: CursorPresentation?,
    private val prefConfig: PreferenceConfiguration,
    private val relativeTouchContextMap: Array<TouchContext?>,
    private val uiCallback: UiCallback
) {

    interface UiCallback {
        fun runOnUi(runnable: Runnable)
        fun isActivityAlive(): Boolean
        fun onLocalCursorFallback()
    }

    private data class CursorShape(
        val bitmap: Bitmap,
        val hotspotX: Int,
        val hotspotY: Int
    )

    private data class RenderKey(
        val shapeId: Int,
        val viewWidth: Int,
        val viewHeight: Int,
        val streamWidth: Int,
        val streamHeight: Int
    )

    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CursorShapeDecoder").apply { isDaemon = true }
    }
    private val sessionGeneration = AtomicInteger()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cursorCache = object : LruCache<Int, CursorShape>(SOURCE_CURSOR_CACHE_KB) {
        override fun sizeOf(key: Int, value: CursorShape): Int = bitmapSizeKb(value.bitmap)
    }
    private val renderedCursorCache = object : LruCache<RenderKey, CursorShape>(RENDERED_CURSOR_CACHE_KB) {
        override fun sizeOf(key: RenderKey, value: CursorShape): Int = bitmapSizeKb(value.bitmap)
    }

    @Volatile
    private var connected = false
    @Volatile
    private var localModeActive = false
    @Volatile
    private var destroyed = false

    private var hostCursorVisible = false
    private var currentShapeId: Int? = null
    private var streamWidth = prefConfig.width.coerceAtLeast(1)
    private var streamHeight = prefConfig.height.coerceAtLeast(1)
    private var loggedUnsupportedHost = false
    private var cursorUpdateTimeout: Runnable? = null

    /**
     * 物理鼠标 / 指针捕获路径使用的本地光标渲染器。
     *
     * 触控板路径的光标渲染器挂在每个 [RelativeTouchContext] 上，而捕获后的物理鼠标走的是
     * [com.limelight.TouchInputHandler] 的相对轴分支，因此这里额外维护一份，保证两条输入通道
     * 画出来的是同一个客户端光标。
     */
    private var mouseCursorRenderer: LocalCursorRenderer? = null
    private var externalDisplayActive = false

    fun setExternalDisplayActive(active: Boolean) {
        if (externalDisplayActive == active) return
        externalDisplayActive = active
        refreshCursorMode()
    }

    fun initializeLocalCursorRenderers(width: Int, height: Int) {
        val presentation = cursorPresentation ?: return

        // 只有「视频同栈光标」才需要物理鼠标这条通道。开关关闭时必须完全不碰它，
        // 否则 LocalCursorRenderer 的 init/setViewDimensions 会把窗口层光标拉回视图中心，
        // 覆盖触控板渲染器已经积分出来的真实位置（行为就与改造前不一致了）。
        if (surfaceCursorActive()) {
            val mouseRenderer = mouseCursorRenderer
            if (mouseRenderer == null) {
                mouseCursorRenderer = LocalCursorRenderer(presentation, width, height).also {
                    if (shouldRenderOverlay()) it.show()
                }
            } else {
                mouseRenderer.setViewDimensions(width, height)
            }
        }

        for (context in relativeTouchContextMap) {
            if (context is RelativeTouchContext) {
                context.initializeLocalCursorRenderer(presentation, width, height)
                context.setEnableLocalCursorRendering(shouldRenderOverlay())
            }
        }
    }

    fun destroyLocalCursorRenderers() {
        mouseCursorRenderer?.let {
            it.hide()
            it.destroy()
        }
        mouseCursorRenderer = null

        for (context in relativeTouchContextMap) {
            if (context is RelativeTouchContext) {
                context.destroyLocalCursorRenderer()
            }
        }
    }

    fun refreshCursorMode() {
        reconcileCursorMode()
        updateRelativeCursorRenderers()
        if (localModeActive) {
            postApplyCurrentCursor()
        }
    }

    fun onConnectionStarted() {
        connected = true
        loggedUnsupportedHost = false
        sessionGeneration.incrementAndGet()
        clearCursorState()
        reconcileCursorMode()
    }

    fun onStreamResolutionChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        streamWidth = width
        streamHeight = height
        renderedCursorCache.evictAll()
        postApplyCurrentCursor()
    }

    fun onCursorUpdate(
        flags: Int,
        shapeId: Int,
        width: Int,
        height: Int,
        hotspotX: Int,
        hotspotY: Int,
        bgraPixels: ByteArray?
    ) {
        if (!connected || !localModeActive || destroyed) return

        val generation = sessionGeneration.get()
        val visible = flags and MoonBridge.LI_CURSOR_UPDATE_FLAG_VISIBLE != 0
        val hasShape = flags and MoonBridge.LI_CURSOR_UPDATE_FLAG_SHAPE != 0

        try {
            decodeExecutor.execute {
                if (destroyed || generation != sessionGeneration.get()) return@execute

                val decodedShape = if (hasShape) {
                    decodeShape(width, height, hotspotX, hotspotY, bgraPixels)
                } else {
                    null
                }

                uiCallback.runOnUi {
                    if (!uiCallback.isActivityAlive() || destroyed ||
                        generation != sessionGeneration.get() || !localModeActive) {
                        return@runOnUi
                    }

                    if (decodedShape != null) {
                        cursorCache.put(shapeId, decodedShape)
                        renderedCursorCache.evictAll()
                    } else if (hasShape) {
                        LimeLog.warning("Cursor: rejected malformed shape id=$shapeId ${width}x$height")
                        return@runOnUi
                    }

                    cancelCursorUpdateTimeout()
                    currentShapeId = shapeId
                    hostCursorVisible = visible
                    applyCurrentCursorOnUiThread()
                }
            }
        } catch (_: RejectedExecutionException) {
            LimeLog.info("Cursor: ignored update after decoder shutdown")
        }
    }

    fun syncCursorWithStream() {
        val presentationView = cursorPresentation?.presentationView ?: return

        val width = streamView.width
        val height = streamView.height
        if (width == 0 || height == 0) return

        val params = presentationView.layoutParams
        if (params is FrameLayout.LayoutParams) {
            params.gravity = Gravity.TOP or Gravity.LEFT
        }

        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            presentationView.layoutParams = params
            renderedCursorCache.evictAll()
        }

        presentationView.pivotX = streamView.pivotX
        presentationView.pivotY = streamView.pivotY
        presentationView.scaleX = streamView.scaleX
        presentationView.scaleY = streamView.scaleY
        presentationView.x = streamView.x
        presentationView.y = streamView.y

        initializeLocalCursorRenderers(width, height)
        postApplyCurrentCursor()
    }

    /** Kept for existing lifecycle call sites while the old socket service is removed. */
    fun stopService() {
        val wasLocal = localModeActive
        if (connected && wasLocal) {
            MoonBridge.setCursorMode(MoonBridge.LI_CURSOR_MODE_VIDEO)
        }

        connected = false
        localModeActive = false
        sessionGeneration.incrementAndGet()
        cancelCursorUpdateTimeout()
        clearCursorState()
        updateRelativeCursorRenderers()
        restoreDefaultCursor()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stopService()
        decodeExecutor.shutdownNow()
        destroyLocalCursorRenderers()
    }

    fun isServiceRunning(): Boolean = localModeActive

    private fun shouldUseLocalMode(): Boolean {
        return CursorModePolicy.shouldUseLocalMode(
            nativePointerSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
            nativePointerEnabled = prefConfig.enableNativeMousePointer,
            touchpadEnabled = prefConfig.touchscreenTrackpad,
            localCursorEnabled = prefConfig.enableLocalCursorRendering,
            hasCursorOverlay = cursorPresentation != null,
            externalDisplayActive = externalDisplayActive,
            videoSurfaceCursorEnabled = surfaceCursorActive()
        )
    }

    private fun shouldRenderOverlay(): Boolean {
        return CursorModePolicy.shouldRenderTouchpadOverlay(
            localModeActive = localModeActive,
            nativePointerSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
            touchpadEnabled = prefConfig.touchscreenTrackpad,
            localCursorEnabled = prefConfig.enableLocalCursorRendering,
            nativePointerEnabled = prefConfig.enableNativeMousePointer,
            videoSurfaceCursorEnabled = surfaceCursorActive()
        )
    }

    /**
     * 新增开关生效时，光标由客户端画进与视频同栈的 Surface，并保留指针捕获以取得相对位移。
     * 外接显示器场景沿用既有策略（系统指针无法被移到副屏），故不生效。
     */
    private fun surfaceCursorActive(): Boolean {
        // 只有视频同栈实现才谈得上「视频层光标」；普通 CursorView 仍走窗口层，
        // 即便偏好开关打开也不该改变它的上报方式。
        return prefConfig.enableVideoSurfaceCursor &&
            cursorPresentation is CursorSurfaceOverlay &&
            !externalDisplayActive
    }

    private fun reconcileCursorMode() {
        if (!connected || destroyed) return

        val shouldUseLocal = shouldUseLocalMode()
        if (shouldUseLocal == localModeActive) return

        if (!shouldUseLocal) {
            cancelCursorUpdateTimeout()
            if (localModeActive) {
                MoonBridge.setCursorMode(MoonBridge.LI_CURSOR_MODE_VIDEO)
            }
            localModeActive = false
            hostCursorVisible = false
            updateRelativeCursorRenderers()
            restoreDefaultCursor()
            return
        }

        val featureFlags = MoonBridge.getHostFeatureFlags()
        if (featureFlags and MoonBridge.LI_FF_CURSOR_SHAPE == 0) {
            logUnsupportedHostOnce("feature flag unavailable")
            localModeActive = false
            updateRelativeCursorRenderers()
            restoreDefaultCursor()
            return
        }

        val result = MoonBridge.setCursorMode(MoonBridge.LI_CURSOR_MODE_LOCAL)
        if (result == MoonBridge.LI_CURSOR_MODE_OK) {
            localModeActive = true
            hostCursorVisible = false
            currentShapeId = null
            hideLocalPresentation()
            updateRelativeCursorRenderers()
            scheduleCursorUpdateTimeout()
            LimeLog.info("Cursor: local rendering mode enabled")
        } else {
            localModeActive = false
            logUnsupportedHostOnce("mode negotiation failed: $result")
            updateRelativeCursorRenderers()
            restoreDefaultCursor()
        }
    }

    private fun logUnsupportedHostOnce(reason: String) {
        if (loggedUnsupportedHost) return
        loggedUnsupportedHost = true
        LimeLog.warning("Cursor: using host-composited cursor ($reason)")
        uiCallback.runOnUi {
            if (uiCallback.isActivityAlive()) {
                uiCallback.onLocalCursorFallback()
            }
        }
    }

    private fun scheduleCursorUpdateTimeout() {
        cancelCursorUpdateTimeout()
        val generation = sessionGeneration.get()
        cursorUpdateTimeout = Runnable {
            if (!connected || !localModeActive || generation != sessionGeneration.get()) return@Runnable
            LimeLog.warning("Cursor: no update received after enabling local mode")
            MoonBridge.setCursorMode(MoonBridge.LI_CURSOR_MODE_VIDEO)
            localModeActive = false
            hostCursorVisible = false
            updateRelativeCursorRenderers()
            restoreDefaultCursor()
            logUnsupportedHostOnce("cursor update timeout")
        }.also { mainHandler.postDelayed(it, CURSOR_UPDATE_TIMEOUT_MS) }
    }

    private fun cancelCursorUpdateTimeout() {
        cursorUpdateTimeout?.let(mainHandler::removeCallbacks)
        cursorUpdateTimeout = null
    }

    /**
     * 把一次相对位移累加到客户端光标上，返回归一化前的绝对坐标（视图像素）。
     *
     * 仅在「视频层低延迟光标」生效时返回非 null；调用方应当用它走绝对坐标上报，
     * 这样主机端不再自行合成光标，客户端画的和主机拿到的是同一个位置。
     */
    fun updateMouseCursorPosition(deltaX: Float, deltaY: Float): FloatArray? {
        if (!surfaceCursorActive()) return null
        val presentation = cursorPresentation ?: return null
        // 布局尚未完成时 streamView 的宽高还是 0，LocalCursorRenderer 会把它夹成 1，
        // 随后 coerceIn(0f, 0f) 把坐标永久钉死在 (0,0)。这里直接放弃这一帧。
        if (streamView.width <= 0 || streamView.height <= 0) return null

        val renderer = mouseCursorRenderer ?: LocalCursorRenderer(
            presentation, streamView.width, streamView.height
        ).also { mouseCursorRenderer = it }

        renderer.show()
        // 主机还没下发过光标位图（或根本不会下发）时用内置箭头兜底，
        // 否则「视频层低延迟光标」在握手完成前、以及非 Sunshine 主机上都是一片空白。
        if (!localModeActive || currentShapeId == null) {
            presentation.setHostCursorVisible(true)
        }
        renderer.updateCursorPosition(deltaX, deltaY)
        return renderer.getCursorAbsolutePosition()
    }

    private fun updateRelativeCursorRenderers() {
        val enabled = shouldRenderOverlay()
        mouseCursorRenderer?.let { if (enabled) it.show() else it.hide() }
        for (context in relativeTouchContextMap) {
            if (context is RelativeTouchContext) {
                context.setEnableLocalCursorRendering(enabled)
            }
        }
    }

    private fun decodeShape(
        width: Int,
        height: Int,
        hotspotX: Int,
        hotspotY: Int,
        pixels: ByteArray?
    ): CursorShape? {
        if (width !in 1..MAX_CURSOR_DIMENSION || height !in 1..MAX_CURSOR_DIMENSION ||
            hotspotX !in 0 until width || hotspotY !in 0 until height ||
            pixels == null || pixels.size != width * height * 4) {
            return null
        }

        val colors = CursorPixelConverter.bgraToArgb(pixels)
        val bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        return CursorShape(bitmap, hotspotX, hotspotY)
    }

    private fun postApplyCurrentCursor() {
        uiCallback.runOnUi {
            if (uiCallback.isActivityAlive() && !destroyed) {
                applyCurrentCursorOnUiThread()
            }
        }
    }

    private fun applyCurrentCursorOnUiThread() {
        if (!localModeActive) {
            restoreDefaultCursor()
            return
        }
        if (!hostCursorVisible) {
            hideLocalPresentation()
            return
        }

        val shapeId = currentShapeId ?: run {
            hideLocalPresentation()
            return
        }
        val source = cursorCache.get(shapeId) ?: run {
            hideLocalPresentation()
            return
        }
        val shape = scaleShapeForView(shapeId, source)

        if (surfaceCursorActive()) {
            // 视频同栈 Surface：SurfaceFlinger 与视频同一次合成事务呈现，
            // 只更新自绘位图，不使用系统指针（PointerIcon 的形状更新要走主线程）。
            cursorPresentation?.setCursorBitmap(shape.bitmap, shape.hotspotX, shape.hotspotY)
            cursorPresentation?.setHostCursorVisible(true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && prefConfig.enableNativeMousePointer) {
            try {
                streamView.pointerIcon = PointerIcon.create(
                    shape.bitmap,
                    shape.hotspotX.toFloat(),
                    shape.hotspotY.toFloat()
                )
            } catch (e: Exception) {
                LimeLog.warning("Cursor: failed to apply native pointer: ${e.message}")
            }
        } else if (shouldRenderOverlay()) {
            cursorPresentation?.setCursorBitmap(shape.bitmap, shape.hotspotX, shape.hotspotY)
            cursorPresentation?.setHostCursorVisible(true)
        }
    }

    private fun scaleShapeForView(shapeId: Int, source: CursorShape): CursorShape {
        val viewWidth = streamView.width.coerceAtLeast(1)
        val viewHeight = streamView.height.coerceAtLeast(1)
        val key = RenderKey(shapeId, viewWidth, viewHeight, streamWidth, streamHeight)
        renderedCursorCache.get(key)?.let { return it }

        val scaleX = viewWidth.toFloat() / streamWidth.toFloat()
        val scaleY = viewHeight.toFloat() / streamHeight.toFloat()
        val targetWidth = (source.bitmap.width * scaleX).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.bitmap.height * scaleY).roundToInt().coerceAtLeast(1)
        if (targetWidth == source.bitmap.width && targetHeight == source.bitmap.height) {
            return source
        }
        val scaled = Bitmap.createScaledBitmap(source.bitmap, targetWidth, targetHeight, true)
        val result = CursorShape(
            scaled,
            (source.hotspotX * scaleX).roundToInt().coerceIn(0, targetWidth - 1),
            (source.hotspotY * scaleY).roundToInt().coerceIn(0, targetHeight - 1)
        )
        renderedCursorCache.put(key, result)
        return result
    }

    private fun bitmapSizeKb(bitmap: Bitmap): Int {
        return ((bitmap.allocationByteCount.toLong() + 1023L) / 1024L)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun hideLocalPresentation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && prefConfig.enableNativeMousePointer) {
            try {
                streamView.pointerIcon = PointerIcon.getSystemIcon(
                    streamView.context,
                    PointerIcon.TYPE_NULL
                )
            } catch (_: Exception) {
            }
        }
        mouseCursorRenderer?.hide()
        cursorPresentation?.setHostCursorVisible(false)
    }

    private fun restoreDefaultCursor() {
        uiCallback.runOnUi {
            if (!uiCallback.isActivityAlive()) return@runOnUi
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && prefConfig.enableNativeMousePointer) {
                try {
                    streamView.pointerIcon = PointerIcon.getSystemIcon(
                        streamView.context,
                        PointerIcon.TYPE_ARROW
                    )
                } catch (_: Exception) {
                }
            }
            mouseCursorRenderer?.hide()
            cursorPresentation?.setHostCursorVisible(false)
            cursorPresentation?.resetToDefault()
        }
    }

    private fun clearCursorState() {
        hostCursorVisible = false
        currentShapeId = null
        cursorCache.evictAll()
        renderedCursorCache.evictAll()
    }

    companion object {
        private const val MAX_CURSOR_DIMENSION = 256
        private const val CURSOR_UPDATE_TIMEOUT_MS = 1500L
        private const val SOURCE_CURSOR_CACHE_KB = 8 * 1024
        private const val RENDERED_CURSOR_CACHE_KB = 8 * 1024
    }
}

internal object CursorModePolicy {
    fun shouldUseLocalMode(
        nativePointerSupported: Boolean,
        nativePointerEnabled: Boolean,
        touchpadEnabled: Boolean,
        localCursorEnabled: Boolean,
        hasCursorOverlay: Boolean,
        externalDisplayActive: Boolean = false,
        videoSurfaceCursorEnabled: Boolean = false
    ): Boolean {
        // Android may keep the system pointer on the tablet's input display. A
        // PointerIcon on the Presentation cannot move it to the video display.
        // Keep the host cursor in the video for external output, including when
        // the saved touchpad preference requests a local overlay on the tablet.
        if (externalDisplayActive) return false

        // 视频同栈光标本身就需要客户端自绘，因此它独立于系统指针开关生效。
        if (videoSurfaceCursorEnabled && hasCursorOverlay) return true

        val nativePointer = nativePointerSupported && nativePointerEnabled
        val touchpadOverlay = touchpadEnabled && localCursorEnabled && hasCursorOverlay
        return nativePointer || touchpadOverlay
    }

    fun shouldRenderTouchpadOverlay(
        localModeActive: Boolean,
        nativePointerSupported: Boolean,
        touchpadEnabled: Boolean,
        localCursorEnabled: Boolean,
        nativePointerEnabled: Boolean,
        videoSurfaceCursorEnabled: Boolean = false
    ): Boolean {
        // 视频同栈光标需要本地绘制，无论触控板本地光标开关是否打开。
        if (localModeActive && videoSurfaceCursorEnabled) return true
        return localModeActive && touchpadEnabled && localCursorEnabled &&
                !(nativePointerSupported && nativePointerEnabled)
    }
}

internal object CursorPixelConverter {
    fun bgraToArgb(pixels: ByteArray): IntArray {
        require(pixels.size % 4 == 0) { "BGRA payload length must be divisible by 4" }
        return IntArray(pixels.size / 4) { index ->
            val offset = index * 4
            val b = pixels[offset].toInt() and 0xFF
            val g = pixels[offset + 1].toInt() and 0xFF
            val r = pixels[offset + 2].toInt() and 0xFF
            val a = pixels[offset + 3].toInt() and 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
