package com.facedemo.app.core

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * OpenCV 原生库初始化。
 *
 * **这个必须做，而且必须在用到第一个 OpenCV 类之前做。**
 * 官方 AAR (`org.opencv:opencv:4.13.0`) 的 AndroidManifest 里只有一个 `uses-sdk`，
 * 既没有 ContentProvider 也没有别的自动初始化机制，所以 `libopencv_java4.so`
 * 不会自己加载。不加载就直接用 `Mat` / `FaceDetectorYN` / `Dnn` 里的 native 方法，
 * 会抛 `UnsatisfiedLinkError: No implementation found for ...`。
 *
 * 坑在于它是 `Error` 而不是 `Exception`：`catch (e: Exception)` 接不住，
 * 线程的默认处理器直接杀进程 —— 表现就是“一打开就闪退”。
 *
 * 这里按三级兜底尝试，并把结果（含版本号）留给界面显示。
 */
object OpenCv {

    private const val TAG = "OpenCv"
    private const val LIB_NAME = "opencv_java4"

    var loaded: Boolean = false
        private set
    var detail: String = "未初始化"
        private set

    fun ensureLoaded(): Boolean {
        if (loaded) return true

        val attempts: List<Pair<String, () -> Boolean>> = listOf(
            "OpenCVLoader.initLocal()" to { OpenCVLoader.initLocal() },
            "OpenCVLoader.initDebug()" to { OpenCVLoader.initDebug() },
            "System.loadLibrary($LIB_NAME)" to {
                System.loadLibrary(LIB_NAME)
                true
            }
        )

        val failures = ArrayList<String>()
        for ((label, action) in attempts) {
            try {
                if (action()) {
                    loaded = true
                    detail = "OpenCV ${OpenCVLoader.OPENCV_VERSION} · $label"
                    Log.i(TAG, "原生库就绪: $detail")
                    return true
                }
                failures.add("$label 返回 false")
                Log.w(TAG, "$label 返回 false")
            } catch (t: Throwable) {
                failures.add("$label: ${t.javaClass.simpleName} ${t.message}")
                Log.e(TAG, "$label 失败", t)
            }
        }

        detail = "OpenCV 原生库加载失败 (" + failures.joinToString("; ") + ")"
        Log.e(TAG, detail)
        return false
    }
}

/**
 * Bitmap -> Mat 的统一入口。
 *
 * **必须走这里，不要直接用 `Utils.bitmapToMat`**：Android 的 ARGB_8888 位图经
 * `Utils.bitmapToMat` 出来是 **4 通道 RGBA**，而 YuNet 检测 / SFace 特征 /
 * PP-HumanSeg 抠像 / GrabCut 都只接受 3 通道输入，直接喂 4 通道会当场抛：
 *
 *     CvException: Number of input channels should be multiple of 3 but got 4
 *     ... in function 'getMemoryShapes'   (convolution_layer.cpp)
 *
 * 真机（OnePlus / Android 16）上就是这么崩的。这里统一转成 **BGR**，与电脑版
 * `cv2.imread` 的通道顺序一致，两端的相似度数值才对得上。
 */
object MatUtil {

    private const val TAG = "MatUtil"

    fun toBgr(bitmap: Bitmap, out: Mat) {
        val rgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            when (rgba.channels()) {
                4 -> Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2BGR)
                1 -> Imgproc.cvtColor(rgba, out, Imgproc.COLOR_GRAY2BGR)
                else -> rgba.copyTo(out)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Bitmap 转 Mat 失败: ${t.message}")
            throw t
        } finally {
            rgba.release()
        }
    }
}
