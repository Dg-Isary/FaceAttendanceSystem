package com.facedemo.app.core

import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 一张检测到的人脸。 */
data class Face(
    val x: Int, val y: Int, val w: Int, val h: Int,
    val score: Float,
    /** YuNet 的原始 1x15 行 (含 5 个关键点), 用于 alignCrop */
    val row: Mat
)

/** 一张人脸与人脸库的比对结果。 */
data class MatchResult(
    val name: String?,
    val similarity: Float,
    val scores: Map<String, Float>,
    val secondName: String? = null,
    val secondSimilarity: Float = 0f,
    val accepted: Boolean = false,
    val decision: String = "陌生人",
    /** 这次判定实际用的阈值（可能是这个人的专属阈值，也可能是全局阈值） */
    val threshold: Float = 0f,
    /** 是否用了个人专属阈值 */
    val customThreshold: Boolean = false
) {
    val margin: Float get() = similarity - secondSimilarity
}

data class GalleryEntry(val name: String, val file: File, val descriptor: FloatArray, val klass: String)

/**
 * 识别引擎: YuNet 检测 + SFace 128 维特征 + 人脸库比对。
 *
 * 与 Python 版 facedemo/engine.py 一一对应, 用的是同一套 ONNX 模型和同一套算法
 * (alignCrop 用 5 个关键点对齐, 相似度 = 余弦相似度), 所以两端结果可以互相印证。
 */
class FaceEngine(private val cfg: Config, private val roster: Roster) {

    companion object {
        private const val TAG = "FaceEngine"
        const val OK = "识别成功"
        const val WEAK = "疑似"
        const val UNKNOWN = "陌生人"
        const val NO_GALLERY = "无人脸库"

        /**
         * 默认识别阈值 / 自动标定的**下界**（v1.1.0：0.36 → 0.50）。
         *
         * 为什么改：用户实测 0.36 的误报率太高（阈值越低越容易把不同人认成同一个人）。
         * 这个 0.36 是从电脑版 `facedemo/engine.py` 的 `default_threshold = 0.36` 抄过来的，
         * 在手机拍摄、人脸库里每人只有一张照片的情况下偏松。现在：
         *   * 手动模式的新装默认值 = 0.50；
         *   * 自动标定算出来的值也**不低于 0.50**（只往上调、不往下探）。
         */
        const val DEFAULT_THRESHOLD = 0.50f
    }

    private val detector: FaceDetectorYN
    private val recognizer: FaceRecognizerSF
    /** 检测时先缩到 procWidth 宽 (与 Python 版一致, 也决定关键点坐标换算) */
    private val procWidth = cfg.procWidth

    var gallery: List<GalleryEntry> = emptyList()
        private set
    var autoThreshold: Float = DEFAULT_THRESHOLD
        private set
    var warnings: List<String> = emptyList()
        private set

    init {
        val yunet = File(cfg.modelsDir, Models.YUNET)
        val sface = File(cfg.modelsDir, Models.SFACE)
        detector = FaceDetectorYN.create(
            yunet.absolutePath, "", Size(320.0, 320.0),
            cfg.detectorScore, 0.3f, 5000
        )
        recognizer = FaceRecognizerSF.create(sface.absolutePath, "")
        Log.i(TAG, "引擎就绪: YuNet + SFace")
    }

    // ------------------------------------------------------------------
    // 检测
    // ------------------------------------------------------------------
    fun detect(bitmap: Bitmap): List<Face> {
        val frame = Mat()
        MatUtil.toBgr(bitmap, frame)
        return try {
            detectMat(frame)
        } finally {
            frame.release()
        }
    }

    private fun detectMat(frame: Mat): List<Face> {
        val scale = if (frame.cols() > procWidth) procWidth.toDouble() / frame.cols() else 1.0
        val small = if (scale < 1.0) {
            Mat().also { Imgproc.resize(frame, it, Size(frame.cols() * scale, frame.rows() * scale)) }
        } else frame
        detector.setInputSize(Size(small.cols().toDouble(), small.rows().toDouble()))
        val faces = Mat()
        detector.detect(small, faces)
        if (scale < 1.0) small.release()
        val count = faces.rows()
        if (count <= 0 || faces.cols() <= 0) {
            faces.release()
            return emptyList()
        }

        // YuNet 的输出是一张 N×15 的 **单通道** CV_32F 矩阵:
        //   0..3 = x, y, w, h   4..13 = 5 个关键点(x,y 各 5 个)   14 = 置信度
        //
        // 关键坑: 单通道 Mat 的 get(r, c) 返回的是**长度 1** 的 double[](就是 (r,c) 那一个数),
        // 不是 [x,y,w,h] 四个数 —— 想按 box[1] 这样取会直接数组越界
        // (真机日志: ArrayIndexOutOfBoundsException: length=1; index=1)。
        // 这里一次性整块读成 FloatArray, 再按行切。
        val stride = faces.cols()
        val flat = FloatArray(count * stride)
        faces.get(0, 0, flat)
        faces.release()

        // 把整行 (框 + 5 个关键点) 一起换算回原图坐标: 只缩放框不缩放关键点,
        // alignCrop 就会对齐到错误位置 (电脑版 v1.0.1 正是踩了这个坑, 相似度趋近 0)。
        // 注意置信度 (最后一列) 不能缩放。
        val inverse = if (scale < 1.0) 1.0 / scale else 1.0
        val out = ArrayList<Face>(count)
        for (i in 0 until count) {
            val base = i * stride
            val values = FloatArray(stride)
            for (c in 0 until stride) {
                values[c] = if (c < stride - 1) (flat[base + c] * inverse).toFloat() else flat[base + c]
            }
            val row = Mat(1, stride, CvType.CV_32F)
            row.put(0, 0, values)
            val x = values[0].toInt()
            val y = values[1].toInt()
            val w = values[2].toInt()
            val h = values[3].toInt()
            out.add(Face(max(0, x), max(0, y), max(1, w), max(1, h), values[stride - 1], row))
        }
        return out
    }

    // ------------------------------------------------------------------
    // 特征
    // ------------------------------------------------------------------
    fun descriptor(bitmap: Bitmap, face: Face): FloatArray? {
        val frame = Mat()
        MatUtil.toBgr(bitmap, frame)
        return try {
            descriptorMat(frame, face)
        } finally {
            frame.release()
        }
    }

    private fun descriptorMat(frame: Mat, face: Face): FloatArray? {
        val aligned = Mat()
        try {
            recognizer.alignCrop(frame, face.row, aligned)
            val feature = Mat()
            recognizer.feature(aligned, feature)
            val floats = FloatArray(128)
            val data = FloatArray((feature.total() * feature.channels()).toInt())
            feature.get(0, 0, data)
            System.arraycopy(data, 0, floats, 0, min(128, data.size))
            feature.release()
            // L2 归一化, 之后点积即余弦相似度
            var norm = 0f
            for (v in floats) norm += v * v
            norm = kotlin.math.sqrt(norm)
            if (norm < 1e-9f) return null
            for (i in floats.indices) floats[i] /= norm
            return floats
        } catch (e: Throwable) {
            Log.w(TAG, "特征提取失败: ${e.message}")
            return null
        } finally {
            aligned.release()
        }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    // ------------------------------------------------------------------
    // 人脸库
    // ------------------------------------------------------------------
    fun loadGallery(): List<GalleryEntry> {
        val entries = ArrayList<GalleryEntry>()
        val messages = ArrayList<String>()
        // 支持 photo/人名.jpg, 也支持 photo/一班/张三.jpg (子目录名当班级, 与电脑版一致)
        val files = ArrayList<Pair<File, String>>()
        cfg.photoDir.listFiles()?.sortedBy { it.name }?.forEach { entry ->
            when {
                entry.isFile && Models.isImage(entry.name) -> files.add(entry to "")
                entry.isDirectory -> entry.listFiles()?.sortedBy { it.name }?.forEach { sub ->
                    if (sub.isFile && Models.isImage(sub.name)) files.add(sub to entry.name)
                }
            }
        }
        for ((file, dirClass) in files) {
            val name = Models.personFromFileName(file.name)
            // 每张照片单独兜底: 某一张坏掉(格式怪、太大、损坏)只跳过这一张,
            // 不能让它把整个初始化/重载拖崩 —— 之前就是一张图出错导致
            // “初始化失败”, 整个 App 用不了。
            var bitmap: Bitmap? = null
            try {
                // 走 ImageUtil: 按 EXIF 把手机拍的照片转正, 并限制尺寸, 免得躺倒或太大
                bitmap = ImageUtil.decodeFile(file)
                if (bitmap == null) {
                    messages.add("${file.name}: 无法读取")
                    continue
                }
                val faces = detect(bitmap)
                if (faces.isEmpty()) {
                    messages.add("${file.name}: 未检测到人脸, 已跳过")
                    continue
                }
                val face = faces.maxByOrNull { it.w * it.h }!!
                val descriptor = descriptor(bitmap, face)
                if (descriptor == null) {
                    messages.add("${file.name}: 特征提取失败")
                    continue
                }
                val klass = dirClass.ifEmpty { roster.classOf(name).ifBlank { Roster.UNASSIGNED } }
                entries.add(GalleryEntry(name, file, descriptor, klass))
            } catch (t: Throwable) {
                messages.add("${file.name}: 处理失败 (${t.javaClass.simpleName}: ${t.message})")
                Log.w(TAG, "跳过照片 ${file.name}", t)
            } finally {
                bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        }
        gallery = entries
        warnings = messages
        calibrate()
        Log.i(TAG, "人脸库: ${entries.size} 张 / ${galleryNames().size} 人")
        return entries
    }

    fun galleryNames(): List<String> = gallery.map { it.name }.distinct().sorted()

    fun classOf(name: String): String =
        gallery.firstOrNull { it.name == name }?.klass
            ?: roster.classOf(name).ifBlank { Roster.UNASSIGNED }

    fun photoCount(name: String): Int = gallery.count { it.name == name }

    fun match(descriptor: FloatArray): MatchResult {
        if (gallery.isEmpty()) {
            return MatchResult(null, 0f, emptyMap(), decision = NO_GALLERY)
        }
        val scores = HashMap<String, Float>()
        for (entry in gallery) {
            val s = similarity(descriptor, entry.descriptor)
            val old = scores[entry.name]
            if (old == null || s > old) scores[entry.name] = s
        }
        val ranking = scores.entries.sortedByDescending { it.value }
        val best = ranking.first()
        return MatchResult(
            name = best.key,
            similarity = best.value,
            scores = scores,
            secondName = ranking.getOrNull(1)?.key,
            secondSimilarity = ranking.getOrNull(1)?.value ?: 0f
        )
    }

    /**
     * 逐帧识别。
     *
     * @param threshold 全局阈值（自动标定值或手动值）
     * @param personThreshold 某个人的专属阈值；返回 null 表示这个人用全局阈值。
     *        判定与「疑似」提示都按**这个人实际用的阈值**来算。
     */
    fun analyze(
        bitmap: Bitmap,
        threshold: Float,
        personThreshold: (String) -> Float? = { null }
    ): List<Pair<Face, MatchResult>> {
        val out = ArrayList<Pair<Face, MatchResult>>()
        val frame = Mat()
        MatUtil.toBgr(bitmap, frame)
        try {
            for (face in detectMat(frame)) {
                val descriptor = descriptorMat(frame, face) ?: continue
                val base = match(descriptor)
                val custom = base.name?.let { personThreshold(it) }
                val used = custom ?: threshold
                val accepted = base.name != null && base.similarity >= used
                val decision = when {
                    gallery.isEmpty() -> NO_GALLERY
                    accepted -> OK
                    base.similarity >= used - 0.10f -> WEAK
                    else -> UNKNOWN
                }
                out.add(
                    face to base.copy(
                        accepted = accepted,
                        decision = decision,
                        threshold = used,
                        customThreshold = custom != null
                    )
                )
            }
        } finally {
            frame.release()
        }
        return out
    }

    /**
     * 自动标定阈值: 同人最低与不同人最高取中点（与 Python 版同算法），
     * v1.1.0 起**结果不低于 DEFAULT_THRESHOLD(0.50)** —— 用户反馈 0.36 误报太多。
     */
    private fun calibrate() {
        val byPerson = gallery.groupBy { it.name }
        val genuine = ArrayList<Float>()
        val impostor = ArrayList<Float>()
        val names = byPerson.keys.sorted()
        for (i in names.indices) {
            val list = byPerson[names[i]] ?: continue
            for (a in list.indices) {
                for (b in a + 1 until list.size) {
                    genuine.add(similarity(list[a].descriptor, list[b].descriptor))
                }
            }
            for (j in i + 1 until names.size) {
                for (x in list) for (y in byPerson[names[j]] ?: emptyList()) {
                    impostor.add(similarity(x.descriptor, y.descriptor))
                }
            }
        }
        val fallback = DEFAULT_THRESHOLD
        var value = when {
            genuine.isNotEmpty() && impostor.isNotEmpty() ->
                (impostor.max() + genuine.min()) / 2f
            impostor.isNotEmpty() -> impostor.max() + 0.04f
            genuine.isNotEmpty() -> genuine.min() - 0.05f
            else -> fallback
        }
        // 样本太少（不同人对比不足 10 组）时，算出来的中点不可靠 → 至少不低于默认值。
        // （旧代码这里是 max(value, fallback * 0.75)，即允许掉到 0.27，太松了，已去掉）
        if (impostor.size < 10) value = maxOf(value, fallback)
        autoThreshold = value.coerceIn(DEFAULT_THRESHOLD, 0.97f)
    }

    // ------------------------------------------------------------------
    /** 取人脸外扩后的取景框 (录入抠像用), 返回裁剪区域。 */
    fun faceRect(face: Face, expand: Float, frameW: Int, frameH: Int): Rect {
        val side = max(face.w, face.h) * expand
        val cx = face.x + face.w / 2.0
        val cy = face.y + face.h / 2.0 - face.h * 0.175
        val x0 = (cx - side / 2).toInt().coerceAtLeast(0)
        val y0 = (cy - side / 2).toInt().coerceAtLeast(0)
        val x1 = (cx + side / 2).toInt().coerceAtMost(frameW)
        val y1 = (cy + side / 2).toInt().coerceAtMost(frameH)
        return Rect(x0, y0, max(8, x1 - x0), max(8, y1 - y0))
    }

    /** 平均颜色 (调试用, 保持与 Python 版类似的接口)。 */
    fun meanColor(mat: Mat): Scalar = Core.mean(mat)
}
