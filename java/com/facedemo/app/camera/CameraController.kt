package com.facedemo.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 相机采集 (CameraX)。对应 Python 版用 cv2.VideoCapture 取帧的那部分。
 *
 * 采集与分析拆开:
 *   * Preview 走 CameraX 的 Surface, 由 PreviewView 显示 (60fps 流畅画面)
 *   * ImageAnalysis 走 KEEP_ONLY_LATEST, 在单独线程上做“位图化 + 旋转”, 交给 onFrame
 *
 * 两边都要求 4:3, 这样画面上的框 (由分析帧算出) 和预览才能对得上。
 *
 * 约定: onFrame 必须在返回前把这一帧用完 (分析/抓拍), 返回后位图会被回收。
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (Bitmap, Int) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "Camera"
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "face-analysis")
    }

    private val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var analysis: ImageAnalysis? = null

    /** 手机姿态监测：放平时暂停相机（省电、降温） */
    private val tiltMonitor = TiltMonitor(context) { flat -> onFlatChanged(flat) }

    /** 放平时是否自动暂停相机（设置里可关） */
    var autoPauseWhenFlat: Boolean = true
        set(value) {
            field = value
            tiltMonitor.enabled = value
            if (!value && pausedByTilt) {
                pausedByTilt = false
                bind()
            }
        }

    /** 逐帧分析的最小间隔（毫秒）：越小越流畅、越费电 */
    var analyzeIntervalMs: Long = 120L

    /** 是否因为手机放平而暂停了相机（Compose 可观察，界面据此显示提示） */
    var pausedByTilt by mutableStateOf(false)
        private set

    /** 用户手动点了“继续使用”：这一次放平不暂停，等重新举起后自动复位 */
    var tiltOverride by mutableStateOf(false)
        private set

    /**
     * 当前停留的页面是否要用相机（识别页 / 录入页，且没打开设置）。
     * 为 false 时相机整体解绑：设置页、签到页、名单页都不需要画面，
     * 尤其“改名单”常常要弄好几分钟，相机一直开着纯属白耗电、白发热。
     */
    @Volatile
    private var pageWantsCamera = true

    private var lastAnalyzeAt = 0L

    val isPausedByTilt: Boolean get() = pausedByTilt
    val tiltRatio: Float get() = tiltMonitor.uprightRatio
    val tiltAvailable: Boolean get() = tiltMonitor.available

    /** 页面切换时调用：不需要画面的页面把相机停掉，回到相机页再绑回来 */
    fun setPageActive(active: Boolean) {
        if (pageWantsCamera == active) return
        pageWantsCamera = active
        mainExecutor.execute {
            if (!active) {
                if (bound) onStatus("已离开相机页，相机暂停")
                unbindQuietly()
            } else if (!(pausedByTilt && !tiltOverride)) {
                // 放平暂停中就先不绑，等手机举起来由 TiltMonitor 负责恢复
                onStatus("回到相机页，相机启动")
                bind()
            }
        }
    }

    private fun unbindQuietly() {
        try {
            provider?.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "暂停相机失败: ${e.message}")
        }
        bound = false
    }

    /** 界面上点“继续使用”时调用：无视当前姿态立刻恢复相机 */
    fun resumeFromTilt() {
        mainExecutor.execute {
            tiltOverride = true
            if (pausedByTilt) {
                pausedByTilt = false
                onStatus("已手动恢复相机")
                bind()
            }
        }
    }

    /**
     * 手机放平 / 举起来时的处理：
     * 放平就把相机整个解绑（预览 + 分析都停，这才是真正省电的一步），举起来再绑回去。
     */
    private fun onFlatChanged(flat: Boolean) {
        if (!autoPauseWhenFlat) return
        mainExecutor.execute {
            if (flat) {
                // 用户明确要求继续用，这一次就不暂停
                if (tiltOverride) return@execute
                if (!pausedByTilt) {
                    pausedByTilt = true
                    try {
                        provider?.unbindAll()
                    } catch (e: Throwable) {
                        Log.w(TAG, "暂停相机失败: ${e.message}")
                    }
                    bound = false
                    onStatus("手机放平，相机已暂停")
                }
            } else {
                // 重新举起，手动覆盖自动失效
                tiltOverride = false
                if (pausedByTilt) {
                    pausedByTilt = false
                    onStatus("已举起，相机恢复")
                    bind()
                }
            }
        }
    }

    /** 后置摄像头默认对着学生, 更适合签到; 录入时可以一键切换成前置 */
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    var bound: Boolean = false
        private set

    val isFront: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    /** 分析帧序号 (与 Python 版的 frame_index 对应, 写进日志) */
    private var frameIndex = 0

    fun attach(view: PreviewView) {
        previewView = view
        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        view.scaleType = PreviewView.ScaleType.FIT_CENTER
        tiltMonitor.start()
        ensureProvider()
    }

    private fun ensureProvider() {
        val existing = provider
        if (existing != null) {
            bind()
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                onStatus("相机就绪")
                bind()
            } catch (e: Throwable) {
                Log.e(TAG, "相机初始化失败", e)
                onStatus("相机初始化失败: ${e.message}")
            }
        }, mainExecutor)
    }

    @Synchronized
    private fun bind() {
        // 两种情况不该绑相机：当前页面不用画面、或放平暂停中（用户手动覆盖除外）
        if (!pageWantsCamera || (pausedByTilt && !tiltOverride)) {
            bound = false
            return
        }
        val cameraProvider = provider ?: return
        val view = previewView ?: return
        try {
            cameraProvider.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "unbindAll: ${e.message}")
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!cameraProvider.hasCamera(selector)) {
            onStatus("设备没有可用的摄像头 (${if (isFront) "前置" else "后置"})")
            // 前后都试不到就放弃, 免得反复报错
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                lensFacing = CameraSelector.LENS_FACING_FRONT
                bind()
            }
            return
        }

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
        val analysisUseCase = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysisUseCase.setAnalyzer(analysisExecutor) { proxy -> handleFrame(proxy) }
        analysis = analysisUseCase

        try {
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysisUseCase)
            preview.setSurfaceProvider(view.surfaceProvider)
            bound = true
            onStatus("相机已启动 (${if (isFront) "前置" else "后置"})")
        } catch (e: Throwable) {
            Log.e(TAG, "绑定相机失败", e)
            bound = false
            onStatus("绑定相机失败: ${e.message}")
        }
    }

    fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        // 主动切镜头说明就是想用相机：放平暂停期间也直接恢复
        if (pausedByTilt) {
            tiltOverride = true
            pausedByTilt = false
        }
        if (provider != null) bind() else ensureProvider()
    }

    // ------------------------------------------------------------------
    private fun handleFrame(proxy: ImageProxy) {
        try {
            // 省电第一步：还没到分析间隔就直接放过这一帧 ——
            // 连"转成 Bitmap"都不做，省掉大量 CPU 和 GC（这是发热的主要来源之一）。
            val now = System.currentTimeMillis()
            if (now - lastAnalyzeAt < analyzeIntervalMs) {
                proxy.close()
                return
            }
            lastAnalyzeAt = now

            val media = proxy.image
            if (media == null) {
                proxy.close()
                return
            }
            val plane = media.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * media.width

            val padded = Bitmap.createBitmap(
                media.width + if (pixelStride > 0) rowPadding / pixelStride else 0,
                media.height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)
            var frame = padded
            if (rowPadding > 0) {
                frame = Bitmap.createBitmap(padded, 0, 0, media.width, media.height)
                padded.recycle()
            }

            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, false)
                if (rotated !== frame) frame.recycle()
                frame = rotated
            }

            frameIndex++
            onFrame(frame, frameIndex)
            if (!frame.isRecycled) frame.recycle()
        } catch (e: Throwable) {
            Log.w(TAG, "分析帧失败: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    fun stop() {
        try {
            provider?.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "停止相机: ${e.message}")
        }
        tiltMonitor.stop()
        analysis = null
        bound = false
    }

    fun shutdown() {
        stop()
        analysisExecutor.shutdown()
    }
}
