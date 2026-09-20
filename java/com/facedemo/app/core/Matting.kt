package com.facedemo.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 抠像去背景 (人脸录入用)。对应 Python 版 facedemo/matting.py。
 *
 *  1. PP-HumanSeg (OpenCV DNN) 出前景概率 → 软 alpha
 *  2. 最大连通域 + 填内部空洞 + 边缘内缩 + 羽化 修边
 *  3. 输出带透明通道的 Bitmap (或合成到指定底色)
 *
 * 模型缺失时退化为 GrabCut (以人脸框为初始前景, 不需要额外模型)。
 */
class Matting(modelsDir: File, private val log: (String) -> Unit = {}) {

    private companion object {
        const val TAG = "Matting"
    }

    private var net: Net? = null
    private var method = "none"

    init {
        val model = File(modelsDir, Models.HUMAN_SEG)
        if (model.exists()) {
            try {
                net = Dnn.readNetFromONNX(model.absolutePath)
                method = "pphumanseg"
            } catch (e: Throwable) {
                Log.e("Matting", "PP-HumanSeg 加载失败: ${e.message}")
                net = null
            }
        }
        if (net == null) {
            method = "grabcut"
            log("[抠像] 使用 GrabCut (慢一些, 不需要分割模型)")
        } else {
            log("[抠像] 人像分割: PP-HumanSeg")
        }
    }

    val methodName: String get() = method

    /** 分割模型是否加载成功（false = 只能靠 GrabCut，慢且糙） */
    val modelLoaded: Boolean get() = net != null

    /**
     * 抠像自检：对着当前画面跑一遍完整流程，把「模型有没有加载、走的哪条路、
     * 抠出多少前景、花了多久」全部报出来。
     *
     * 这是回答「为什么抠出来是个方块」的关键 ——
     *   * 前景占比 ≈ 100% ⇒ alpha 全是 1，等于没抠（要么开关关了，要么两条路都失败）
     *   * 耗时只有几毫秒 ⇒ 模型根本没跑
     */
    fun selfTest(
        bitmap: Bitmap,
        faceRect: Rect?,
        erodePercent: Float,
        featherPercent: Float,
        enabled: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("抠像开关: ").append(if (enabled) "开" else "关（只裁切，背景保持原样）").append('\n')
        sb.append("分割模型: ").append(if (modelLoaded) "已加载 (PP-HumanSeg)" else "未加载，只能用 GrabCut").append('\n')
        sb.append("输入取景框: ").append(bitmap.width).append('x').append(bitmap.height)
        if (faceRect != null) {
            sb.append("，脸框 ").append(faceRect.width).append('x').append(faceRect.height)
        }
        sb.append('\n')
        sb.append("参数: 边缘收紧 ").append(erodePercent).append("% / 羽化 ")
            .append(featherPercent).append("%\n")

        val started = System.currentTimeMillis()
        val alpha = alphaMask(bitmap, faceRect, erodePercent, featherPercent, enabled)
        val cost = System.currentTimeMillis() - started
        val ratio = if (alpha.isEmpty()) 0f else alpha.count { it > 0.5f }.toFloat() / alpha.size

        sb.append("\n实际走的方法: ").append(method).append('\n')
        sb.append("耗时: ").append(cost).append(" ms\n")
        sb.append("前景占比: ").append((ratio * 100).toInt()).append("%\n")
        sb.append("结论: ")
        sb.append(
            when {
                !enabled -> "抠像开关是关的，所以是方块 —— 到设置里把它打开"
                ratio > 0.98f -> "抠出来几乎是整块矩形：说明人像分割没起作用（开关虽开但没生效）"
                ratio < 0.02f -> "抠出来几乎全透明：分割没找到人像，建议换光线/背景再试"
                cost < 60 -> "耗时很短，可能是走的 GrabCut 而不是模型（模型没加载成功）"
                else -> "正常：已按人像轮廓抠出，占比 ${(ratio * 100).toInt()}%"
            }
        ).append('\n')
        return sb.toString()
    }

    // ------------------------------------------------------------------
    /**
     * 计算 0~1 的软 alpha 图 (长度 w*h)。
     * @param faceRect 人脸框, 用于换算内缩/羽化强度与 GrabCut 初始化
     * @param erodePercent   边缘内缩强度（相对人脸宽度的百分比，用来去掉背景光晕）
     * @param featherPercent 边缘羽化强度（同上）
     */
    fun alphaMask(
        bitmap: Bitmap,
        faceRect: Rect?,
        erodePercent: Float = 2.2f,
        featherPercent: Float = 2.2f,
        enabled: Boolean = true
    ): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        if (!enabled) {
            log("[抠像] 抠像已关闭，只做裁切（背景保持原样）")
            return FloatArray(w * h) { 1f }
        }
        val src = Mat()
        MatUtil.toBgr(bitmap, src)

        var prob: Mat? = null
        try {
            val reference = (faceRect?.width ?: (w * 0.08)).toFloat()
            val erodePx = max(0, (reference * erodePercent / 100f).roundToInt())
            val featherPx = max(0, (reference * featherPercent / 100f).roundToInt())

            // ---- 1) 人像分割模型 ----
            val seg = try {
                if (net != null) humanSegProb(src) else null
            } catch (t: Throwable) {
                Log.e(TAG, "人像分割失败", t)
                log("[抠像] 分割模型出错: ${t.javaClass.simpleName} ${t.message}")
                null
            }

            var used = "none"
            if (seg != null && plausible(seg)) {
                prob = seg
                used = "pphumanseg"
            } else {
                if (seg != null) {
                    log("[抠像] 分割结果不可信（前景占比 ${(ratioOf(seg) * 100).toInt()}%），改用 GrabCut")
                    seg.release()
                }
                // ---- 2) GrabCut 兜底（不需要模型，按人脸框初始化）----
                prob = try {
                    grabCutProb(src, faceRect)
                } catch (t: Throwable) {
                    Log.e(TAG, "GrabCut 失败", t)
                    log("[抠像] GrabCut 也失败: ${t.message}")
                    null
                }
                used = "grabcut"
            }
            if (prob == null) {
                log("[抠像] 两种方法都没成功，本次不抠像（背景保留）")
                return FloatArray(w * h) { 1f }
            }
            method = used

            // ---- 3) 精细化：内部每一步都兜底，失败就用未精修的掩码 ----
            val refined = try {
                refine(prob, erodePx, featherPx, src)
            } catch (t: Throwable) {
                Log.e(TAG, "抠像后处理失败，退回原始掩码", t)
                log("[抠像] 后处理失败（${t.javaClass.simpleName} ${t.message}），直接用原始掩码")
                prob.clone()
            }

            val out = FloatArray(w * h)
            refined.get(0, 0, out)
            refined.release()

            val ratio = out.count { it > 0.5f }.toFloat() / out.size
            log("[抠像] $used 完成，前景占比 ${(ratio * 100).toInt()}%，羽化 sigma=${max(1.0, featherPx / 2.0)}")
            if (ratio < 0.02f || ratio > 0.98f) {
                log("[抠像] ! 前景占比异常，已按不抠像处理（避免出全透明/全背景的废图）")
                return FloatArray(w * h) { 1f }
            }
            return out
        } catch (t: Throwable) {
            Log.e(TAG, "抠像整体失败", t)
            log("[抠像] 失败: ${t.javaClass.simpleName} ${t.message}，本次不抠像")
            return FloatArray(w * h) { 1f }
        } finally {
            prob?.release()
            src.release()
        }
    }

    /** 前景占比是否可信（既不是几乎全背景、也不是几乎全前景）。 */
    private fun plausible(prob: Mat): Boolean {
        val r = ratioOf(prob)
        return r > 0.02f && r < 0.98f
    }

    private fun ratioOf(prob: Mat): Float {
        val h = prob.rows()
        val w = prob.cols()
        if (w <= 0 || h <= 0) return 0f
        val data = FloatArray(w * h)
        prob.get(0, 0, data)
        return data.count { it > 0.5f }.toFloat() / data.size
    }

    /** PP-HumanSeg: 192x192 RGB, 输出 2 类概率, 取前景概率并放大回原尺寸。 */
    private fun humanSegProb(src: Mat): Mat {
        val blob = Dnn.blobFromImage(
            src, 1.0 / 255.0, Size(192.0, 192.0), Scalar(0.5, 0.5, 0.5),
            true, false, CvType.CV_32F
        )
        val network = net!!
        network.setInput(blob)
        val output = network.forward()          // [1, 2, 192, 192]

        // 注意：输出是 **4 维** Mat（1×2×192×192）。不要直接对它用 get(row, col, arr) ——
        // Java 那个接口内部走的是 Mat::row()/colRange()，只对 2 维矩阵有定义，
        // 对 4 维矩阵属于未定义行为（可能读到错的数据 → 掩码变空 → 看起来“抠像没生效”）。
        // 先 reshape 成 1×N 的连续二维视图再读，安全且更快。
        val flat = output.reshape(1, 1)
        val data = FloatArray(flat.total().toInt())
        flat.get(0, 0, data)
        flat.release()
        output.release()
        blob.release()

        val plane = 192 * 192
        if (data.size < plane * 2) {
            throw IllegalStateException("分割输出尺寸异常: ${data.size} (期望 ${plane * 2})")
        }
        val prob = Mat(192, 192, CvType.CV_32F)
        val values = FloatArray(plane)
        for (i in 0 until plane) {
            val a = data[i]              // 背景 logit
            val b = data[plane + i]      // 人像 logit
            val m = max(a, b)
            val ea = Math.exp((a - m).toDouble())
            val eb = Math.exp((b - m).toDouble())
            values[i] = (eb / (ea + eb)).toFloat()
        }
        prob.put(0, 0, values)
        val resized = Mat()
        Imgproc.resize(prob, resized, Size(src.cols().toDouble(), src.rows().toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR)
        prob.release()
        return resized
    }

    /** GrabCut 兜底: 以人脸框外扩区域作为初始前景。 */
    private fun grabCutProb(src: Mat, faceRect: Rect?): Mat {
        val h = src.rows()
        val w = src.cols()
        val rect = if (faceRect != null) {
            val x0 = max(0, (faceRect.x - faceRect.width * 0.9).toInt())
            val y0 = max(0, (faceRect.y - faceRect.height * 1.0).toInt())
            val x1 = min(w, (faceRect.x + faceRect.width * 1.9).toInt())
            val y1 = min(h, (faceRect.y + faceRect.height * 2.0).toInt())
            Rect(x0, y0, max(8, x1 - x0), max(8, y1 - y0))
        } else {
            Rect((w * 0.15).toInt(), (h * 0.08).toInt(), (w * 0.7).toInt(), (h * 0.84).toInt())
        }
        val mask = Mat.zeros(h, w, CvType.CV_8UC1)
        val bgd = Mat.zeros(1, 65, CvType.CV_64FC1)
        val fgd = Mat.zeros(1, 65, CvType.CV_64FC1)
        try {
            Imgproc.grabCut(src, mask, rect, bgd, fgd, 3, Imgproc.GC_INIT_WITH_RECT)
            val prob = Mat(h, w, CvType.CV_32F)
            val maskData = ByteArray(h * w)
            mask.get(0, 0, maskData)
            val values = FloatArray(h * w)
            for (i in values.indices) {
                val v = maskData[i].toInt()
                values[i] = if (v == Imgproc.GC_FGD || v == Imgproc.GC_PR_FGD) 1f else 0f
            }
            prob.put(0, 0, values)
            return prob
        } finally {
            mask.release(); bgd.release(); fgd.release()
        }
    }

    /** 最大连通域 -> 填洞 -> 内缩 -> 软掩码 -> 边界去色, 返回 CV_32F 的 alpha。 */
    private fun refine(prob: Mat, erodePx: Int, featherPx: Int, src: Mat): Mat {
        val h = prob.rows()
        val w = prob.cols()

        // 二值化
        val binary = Mat()
        Imgproc.threshold(prob, binary, 0.5, 255.0, Imgproc.THRESH_BINARY)
        binary.convertTo(binary, CvType.CV_8UC1)

        // 最大连通域 + 所有「足够大」的连通域
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8, CvType.CV_32S)
        if (count > 1) {
            var bestArea = 0.0
            for (i in 1 until count) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area > bestArea) bestArea = area
            }
            // 保留面积 ≥ 最大块 10% 的所有连通域：只留最大的一块会把
            // 抬起来的手臂、散开的头发整块丢掉（也是「抠出来不像人」的原因之一）
            val minArea = bestArea * 0.10
            val keepLabel = BooleanArray(count)
            for (i in 1 until count) {
                keepLabel[i] = stats.get(i, Imgproc.CC_STAT_AREA)[0] >= minArea
            }
            val labelData = IntArray(w * h)
            labels.get(0, 0, labelData)
            val keep = Mat.zeros(h, w, CvType.CV_8UC1)
            val keepData = ByteArray(w * h)
            for (i in keepData.indices) {
                val label = labelData[i]
                if (label in 1 until count && keepLabel[label]) keepData[i] = 255.toByte()
            }
            keep.put(0, 0, keepData)
            binary.release()
            labels.release(); stats.release(); centroids.release()
            return refineWithMask(prob, keep, erodePx, featherPx, src)
        }
        labels.release(); stats.release(); centroids.release()
        return refineWithMask(prob, binary, erodePx, featherPx, src)
    }

    /**
     * 生成最终的软 alpha。
     *
     * 关键改动（v1.0.0 修「抠不干净」）：
     *  1. 以前是 `alpha = prob × 硬掩码`，羽化之后又乘了一次同一个硬掩码 ——
     *     等于把刚羽化出来的过渡带又切掉了，边界依旧是硬边，背景色就留在边上。
     *     现在改成 **对掩码本身做高斯模糊得到软权重**，边界是平滑衰减的。
     *  2. 内缩（erode）只在生成软权重前做一次，用来把贴着轮廓的一圈背景色去掉。
     *  3. 新增**边界去色**：用取景框四周估出背景色，在「外扩掩码 − 内缩掩码」这条
     *     边界带里，凡颜色接近背景色的像素再把 alpha 压低 —— 这一步专门清掉
     *     头发缝里、肩膀边缘残留的背景（证件照最显脏的地方）。
     */
    private fun refineWithMask(prob: Mat, maskIn: Mat, erodePx: Int, featherPx: Int, src: Mat): Mat {
        val h = maskIn.rows()
        val w = maskIn.cols()

        // 填内部空洞
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(maskIn, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val outer = Mat.zeros(h, w, CvType.CV_8UC1)
        if (contours.isNotEmpty()) {
            Imgproc.drawContours(outer, contours, -1, Scalar(255.0), -1)
        }
        hierarchy.release()
        contours.forEach { it.release() }
        maskIn.release()

        // 形态学闭运算：桥接头发丝那种细小断裂，别让轮廓上出现一排小缺口
        val closeSize = 7.0
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(closeSize, closeSize))
        Imgproc.morphologyEx(outer, outer, Imgproc.MORPH_CLOSE, closeKernel)
        closeKernel.release()

        // 内缩（收紧轮廓，先去掉紧贴边缘的背景光晕）
        val inner = outer.clone()
        if (erodePx > 0) {
            val size = (erodePx * 2 + 1).toDouble()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size, size))
            Imgproc.erode(inner, inner, kernel)
            kernel.release()
        }

        // 软权重：对收紧后的掩码做高斯模糊 → 边界平滑过渡，而不是硬切
        val weight = Mat()
        inner.convertTo(weight, CvType.CV_32F, 1.0 / 255.0)
        if (featherPx > 0) {
            val sigma = max(1.0, featherPx / 2.0)
            Imgproc.GaussianBlur(weight, weight, Size(0.0, 0.0), sigma)
        }
        val alpha = Mat()
        Core.multiply(prob, weight, alpha)

        // 边界带 = 外扩掩码 − 内缩掩码；在这条带里做背景色去除
        val band = Mat()
        Core.subtract(outer, inner, band)
        decontaminate(alpha, band, outer, src)

        outer.release()
        inner.release()
        weight.release()
        band.release()
        return alpha
    }

    /**
     * 边界去色：把边界带里「长得像背景色」的半透明像素再压暗。
     *
     * 这是清掉「一圈白边」的关键一步（实测：不去色时头发/肩膀轮廓上会留一条
     * 背景色的亮边，去掉之后边缘才干净）。三条保护措施避免误伤本人：
     *
     *  1. 背景色**只从掩码外的边框像素**采样（以前从整圈边框采样，人站在边上时
     *     会把衣服颜色当背景色，然后去把自己人像压透明 —— 那样脸会发灰）；
     *  2. 只在 **外扩掩码 − 内缩掩码** 这条带里动手，脸内部不碰；
     *  3. 只压 **alpha < 0.9 的半透明像素**，实心的人像（alpha≈1）一律不碰，
     *     所以脸上的高光不会被当成背景吃掉。
     */
    private fun decontaminate(alpha: Mat, bandMask: Mat, outerMask: Mat, src: Mat) {
        val w = alpha.cols()
        val h = alpha.rows()
        if (w <= 0 || h <= 0) return
        val band = ByteArray(w * h)
        bandMask.get(0, 0, band)
        val outer = ByteArray(w * h)
        outerMask.get(0, 0, outer)
        val pixels = ByteArray(w * h * 3)
        src.get(0, 0, pixels)

        val border = max(2, min(w, h) / 30)
        var sumB = 0.0
        var sumG = 0.0
        var sumR = 0.0
        var samples = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x >= border && y >= border && x < w - border && y < h - border) continue
                val i = y * w + x
                if (outer[i].toInt() != 0) continue      // 只取掩码外的像素当背景样本
                val j = i * 3
                sumB += pixels[j].toInt() and 0xFF
                sumG += pixels[j + 1].toInt() and 0xFF
                sumR += pixels[j + 2].toInt() and 0xFF
                samples++
            }
        }
        // 背景样本太少（人占满了取景框边缘）就干脆不做，宁可不处理也别乱压
        if (samples < 50) return
        val bgB = sumB / samples
        val bgG = sumG / samples
        val bgR = sumR / samples

        val values = FloatArray(w * h)
        alpha.get(0, 0, values)
        val threshold = 100.0      // 颜色距离阈值（0~441）
        var touched = 0
        for (i in 0 until w * h) {
            if (band[i].toInt() == 0) continue
            if (values[i] <= 0.01f || values[i] >= 0.9f) continue
            val j = i * 3
            val db = (pixels[j].toInt() and 0xFF) - bgB
            val dg = (pixels[j + 1].toInt() and 0xFF) - bgG
            val dr = (pixels[j + 2].toInt() and 0xFF) - bgR
            val dist = kotlin.math.sqrt(db * db + dg * dg + dr * dr)
            if (dist < threshold) {
                values[i] *= (dist / threshold).toFloat().coerceIn(0f, 1f)
                touched++
            }
        }
        if (touched > 0) alpha.put(0, 0, values)
    }

    // ------------------------------------------------------------------
    /** 按 alpha 生成带透明通道的 Bitmap (RGBA_8888)。 */
    fun cutout(bitmap: Bitmap, alpha: FloatArray): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val a = (alpha.getOrElse(i) { 1f }.coerceIn(0f, 1f) * 255).roundToInt()
            out[i] = (pixels[i] and 0x00FFFFFF) or (a shl 24)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** 按 alpha 合成到纯色底上 (用于预览/过程图)。 */
    fun composeOn(bitmap: Bitmap, alpha: FloatArray, background: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val br = Color.red(background)
        val bg = Color.green(background)
        val bb = Color.blue(background)
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val a = alpha.getOrElse(i) { 1f }.coerceIn(0f, 1f)
            val r = (Color.red(pixels[i]) * a + br * (1 - a)).roundToInt()
            val g = (Color.green(pixels[i]) * a + bg * (1 - a)).roundToInt()
            val b = (Color.blue(pixels[i]) * a + bb * (1 - a)).roundToInt()
            out[i] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** 前景占比 (调试信息)。 */
    fun foregroundRatio(alpha: FloatArray): Float =
        if (alpha.isEmpty()) 0f else alpha.count { it > 0.5f }.toFloat() / alpha.size

    fun release() {
        net = null
    }
}
