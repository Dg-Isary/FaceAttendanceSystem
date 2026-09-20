package com.facedemo.app.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.opencv.core.Rect
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class EnrollResult(
    val ok: Boolean,
    val name: String,
    val message: String,
    val saved: List<String> = emptyList(),
    val debugFiles: List<String> = emptyList(),
    val facesFound: Int = 0,
    val matting: String = "",
    val verifySimilarity: Float? = null,
    val verified: Boolean = false,
    val klass: String = ""
)

/**
 * 人脸录入: 从画面或图片里抠出人像、去掉背景, 存成人脸库照片。
 * 对应 Python 版 facedemo/enroll.py, 流程完全一致:
 *
 *   1. 找画面里最大的人脸 (没人脸则拒绝)
 *   2. 按人脸框外扩成“人像取景框”, 先裁剪再抠像
 *   3. PP-HumanSeg 出软 alpha, 去掉背景 (默认存带透明通道的 PNG)
 *   4. 存成 photo/<人名>_<序号>.png —— 文件名即人名, 下次加载自动进人脸库
 *   5. 回读保存的图片重新检测+提特征, 与录入时的特征比对, 确认这份照片可用
 */
class FaceEnroller(
    private val cfg: Config,
    private val engine: FaceEngine,
    private val matting: Matting,
    private val roster: Roster? = null,
    private val log: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "Enroll"
        private val INVALID_CHARS = Regex("[\\\\/:*?\"<>|\r\n\t]")

        /** 输出规格: idphoto = 标准一寸照, cutout = 原始抠像裁切 */
        const val OUTPUT_ID_PHOTO = "idphoto"
        const val OUTPUT_CUTOUT = "cutout"

        /** 标准一寸照 25mm × 35mm，按 300dpi 输出 = 295 × 413 像素 */
        const val ID_PHOTO_WIDTH = 295
        const val ID_PHOTO_HEIGHT = 413

        /**
         * 证件照取景比例（对齐一寸照国标）：
         * 头高（发顶→下巴）占照片高度的 60%，头顶留白 8%。
         */
        private const val HEAD_HEIGHT_RATIO = 0.60f
        private const val HEAD_TOP_MARGIN = 0.08f

        /** 抠像量不出发顶时的兜底：头高 ≈ 脸框(额→下巴)高度的 1.28 倍 */
        private const val HEAD_HEIGHT_ESTIMATE = 1.28f

        /** 背景名 -> 颜色；transparent 返回 null（保留透明通道） */
        fun backgroundOf(name: String): Int? = when (name) {
            "white" -> Color.rgb(255, 255, 255)
            "blue" -> Color.rgb(67, 142, 219)      // 证件照常用的标准蓝
            "red" -> Color.rgb(214, 45, 45)        // 证件照常用的标准红
            "gray" -> Color.rgb(128, 128, 128)
            else -> null                            // transparent
        }

        fun backgroundName(name: String): String = when (name) {
            "white" -> "白底"
            "blue" -> "蓝底"
            "red" -> "红底"
            "gray" -> "灰底"
            else -> "透明底"
        }
    }

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
    val debugDir: File = cfg.debugDir

    val mattingName: String get() = matting.methodName

    fun sanitizeName(name: String): String =
        INVALID_CHARS.replace(name.trim(), "").trim('.', ' ').take(32)

    /** 同一个人下一张照片的序号 (张三.jpg 视为第 1 张)。 */
    private fun nextIndex(folder: File, name: String): Int {
        var highest = 0
        val pattern = Regex("^" + Regex.escape(name) + "(?:[\\s_\\-.](\\d+))?$")
        folder.listFiles()?.forEach { file ->
            val stem = file.name.substringBeforeLast('.')
            val match = pattern.find(stem) ?: return@forEach
            val value = match.groupValues[1].toIntOrNull() ?: 1
            if (value > highest) highest = value
        }
        return highest + 1
    }

    fun personFiles(): Map<String, List<String>> {
        val people = LinkedHashMap<String, MutableList<String>>()
        cfg.photoDir.listFiles()
            ?.filter { it.isFile && Models.isImage(it.name) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                people.getOrPut(Models.personFromFileName(file.name)) { ArrayList() }.add(file.name)
            }
        return people
    }

    // ------------------------------------------------------------------
    fun enrollFrame(frame: Bitmap, name: String, source: String = "摄像头", klass: String = ""): EnrollResult {
        val person = sanitizeName(name)
        if (person.isEmpty()) return EnrollResult(false, "", "人名不能为空")

        val faces = engine.detect(frame)
        if (faces.isEmpty()) return EnrollResult(false, person, "画面里没有检测到人脸", facesFound = 0)
        val face = faces.maxByOrNull { it.w * it.h }!!
        if (faces.size > 1) {
            log("[录入] 检测到 ${faces.size} 张人脸, 使用最大的一张 (${face.x},${face.y},${face.w},${face.h})")
        }

        val descriptor = engine.descriptor(frame, face)
            ?: return EnrollResult(false, person, "人脸特征提取失败, 请换个角度重试", facesFound = faces.size)

        // ---- 取景 + 抠像 ----
        val box: Rect = engine.faceRect(face, cfg.enrollExpand, frame.width, frame.height)
        val patch = try {
            Bitmap.createBitmap(frame, box.x, box.y, box.width, box.height)
        } catch (e: Throwable) {
            null
        } ?: return EnrollResult(false, person, "取景失败", facesFound = faces.size)

        val localFace = Rect(face.x - box.x, face.y - box.y, face.w, face.h)
        var alpha = matting.alphaMask(
            patch, localFace, cfg.mattingErode, cfg.mattingFeather, cfg.mattingEnabled
        )

        // 抠像失败(几乎全透明)时退化为不抠像, 保证录入可用
        var note = ""
        var method = matting.methodName
        if (method != "none" && alpha.count { it > 0.5f } < 0.05f * alpha.size) {
            log("[录入] 抠像结果几乎全透明, 退回不抠像以保证录入可用")
            alpha = FloatArray(alpha.size) { 1f }
            method = "none"
            note = " (抠像失败, 已按未抠像保存)"
        }
        val ratio = if (alpha.isEmpty()) 1f else alpha.count { it > 0.5f }.toFloat() / alpha.size

        val background = backgroundOf(cfg.mattingBackground)
        val oneInch = cfg.enrollOutput != OUTPUT_CUTOUT
        val imageToSave: Bitmap = if (oneInch) {
            // 标准一寸照: 25mm × 35mm @300dpi = 295 × 413 像素
            composeIdPhoto(patch, alpha, localFace, background)
        } else if (background == null) {
            matting.cutout(patch, alpha)
        } else {
            matting.composeOn(patch, alpha, background)
        }
        val ext = if (background == null) ".png" else ".jpg"

        // ---- 落盘 ----
        val folder = cfg.photoDir
        folder.mkdirs()
        if (cfg.enrollReplace) {
            val removed = removePerson(folder, person)
            if (removed > 0) log("[录入] 已删除 $person 的旧照片 $removed 张")
        }
        val index = nextIndex(folder, person)
        val fileName = "${person}_$index$ext"
        val target = File(folder, fileName)
        val format = if (background == null) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (background == null) 100 else 95
        val written = try {
            FileOutputStream(target).use { imageToSave.compress(format, quality, it) }
        } catch (e: Throwable) {
            false
        }
        if (!written) {
            patch.recycle(); imageToSave.recycle()
            return EnrollResult(false, person, "写入失败: $fileName", facesFound = faces.size)
        }

        val debugFiles = saveDebug(person, patch, alpha, face)
        if (imageToSave !== patch) imageToSave.recycle()
        patch.recycle()

        // ---- 名单: 记下这个人是哪个班的 ----
        var finalClass = klass.trim()
        if (roster != null) {
            val student = roster.add(person, klass = finalClass, save = true)
            if (student != null) finalClass = student.klass
        }
        if (finalClass.isEmpty() && roster != null) finalClass = roster.classOf(person)

        // ---- 回读校验 ----
        val (verified, similarity) = verify(target, descriptor)
        val spec = if (oneInch) {
            "一寸照 ${ID_PHOTO_WIDTH}×${ID_PHOTO_HEIGHT}" +
                (if (background == null) " 透明底" else " " + backgroundName(cfg.mattingBackground))
        } else {
            "抠像裁切 " + (if (background == null) "透明底" else backgroundName(cfg.mattingBackground))
        }
        var message = "已录入 $person" +
            (if (finalClass.isNotEmpty()) " [$finalClass]" else "") +
            ": $fileName ($spec, $method, 前景占比 ${(ratio * 100).toInt()}%)$note"
        message += if (!verified) {
            "  回读校验未通过, 这张照片可能无法用于识别, 建议重录"
        } else {
            "  校验相似度 " + String.format(Locale.US, "%.3f", similarity ?: 0f)
        }
        if (!verified) log("[录入] ! $fileName 回读校验未通过")
        log("[录入] $message")

        return EnrollResult(
            ok = true, name = person, message = message, saved = listOf(fileName),
            debugFiles = debugFiles, facesFound = faces.size, matting = method,
            verifySimilarity = similarity, verified = verified, klass = finalClass
        )
    }

    /** 从已有图片录入 (没摄像头时也能用, 例如从相册选一张老照片)。 */
    fun enrollFile(file: File, name: String? = null, klass: String = ""): EnrollResult {
        val bitmap = ImageUtil.decodeFile(file)
            ?: return EnrollResult(false, name.orEmpty(), "无法读取图片: ${file.name}")
        val person = sanitizeName(name ?: file.nameWithoutExtension)
        log("[录入] 从图片录入: ${file.name} -> $person" + (if (klass.isNotEmpty()) " [$klass]" else ""))
        return BitmapHolder.use(bitmap) { enrollFrame(it, person, source = file.name, klass = klass) }
    }

    // ------------------------------------------------------------------
    /**
     * 把抠像结果摆成**标准一寸照**：25mm × 35mm，按 300dpi 输出 295 × 413 像素。
     *
     * 取景规则（和照相馆的做法一致）：脸部框高度 ≈ 照片高度的 52%，
     * 脸部中心略高于画面中线（上方留出头顶空间）。
     *
     * @param background 底色；null = 透明底（输出 PNG）
     */
    /**
     * 量出「发顶在哪一行、头有多高」。
     *
     * 只认**脸框左右各扩 1/4** 这条竖直带里的像素，并且要求某一行至少有 3 个不透明像素
     * 才算“这一行有头” —— 抠像边缘免不了有零星噪点，用 `any()` 会把噪声当成发顶，
     * 量出来的头高就完全错了（我第一版就是这么错的：头被放大到顶到上边）。
     *
     * @return (头高, 发顶行号)；发顶行号为 0 表示头顶正好在取景框上沿
     */
    private fun headSpan(alpha: FloatArray, width: Int, height: Int, face: Rect): Pair<Float, Int> {
        val left = maxOf(0, face.x - face.width / 4)
        val right = minOf(width, face.x + face.width + face.width / 4)
        var top = -1
        for (y in 0 until height) {
            val base = y * width
            var count = 0
            for (x in left until right) {
                if (alpha[base + x] > 0.5f) {
                    count++
                    if (count >= 3) { top = y; break }
                }
            }
            if (top >= 0) break
        }
        val chin = face.y + face.height
        val measured = if (top >= 0) (chin - top).toFloat() else -1f
        // 量出来的头高离脸框太远就不可信（抠像把背景也算进来了 / 完全没有前景）→ 退回估算
        val believable = measured >= face.height * 1.05f && measured <= face.height * 2.0f
        return if (believable) {
            measured to top
        } else {
            val estimate = face.height * HEAD_HEIGHT_ESTIMATE
            estimate to maxOf(0, (chin - estimate).roundToInt())
        }
    }

    private fun composeIdPhoto(patch: Bitmap, alpha: FloatArray, face: Rect, background: Int?): Bitmap {
        val cut = matting.cutout(patch, alpha)
        val (headHeight, headTop) = headSpan(alpha, patch.width, patch.height, face)

        var scale = ID_PHOTO_HEIGHT * HEAD_HEIGHT_RATIO / headHeight
        // 铺满画布，免得底色区域露出白边
        val cover = maxOf(
            ID_PHOTO_WIDTH.toFloat() / patch.width,
            ID_PHOTO_HEIGHT.toFloat() / patch.height
        )
        if (cover > scale) scale = cover
        // 人脸很小的时候会放大很多倍, 限制一下缩放后的大小, 免得内存爆掉
        val maxSide = maxOf(ID_PHOTO_WIDTH, ID_PHOTO_HEIGHT) * 4f
        val scaledSide = maxOf(patch.width, patch.height) * scale
        if (scaledSide > maxSide) scale *= maxSide / scaledSide
        // 夹完再确认一次铺满 —— 否则会出现一条底色空边（看着像“抠坏了”）
        if (scale < cover) scale = cover

        val scaledWidth = maxOf(1, (patch.width * scale).roundToInt())
        val scaledHeight = maxOf(1, (patch.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(cut, scaledWidth, scaledHeight, true)

        val canvas = Bitmap.createBitmap(ID_PHOTO_WIDTH, ID_PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        val painter = Canvas(canvas)
        if (background != null) painter.drawColor(background)
        val dx = ID_PHOTO_WIDTH / 2f - (face.x + face.width / 2f) * scale
        val dy = ID_PHOTO_HEIGHT * HEAD_TOP_MARGIN - headTop * scale
        painter.drawBitmap(scaled, dx, dy, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== cut) scaled.recycle()
        cut.recycle()
        return canvas
    }

    /** 回读刚保存的照片, 确认还能检测到人脸并算得出接近的特征。 */
    private fun verify(saved: File, descriptor: FloatArray): Pair<Boolean, Float?> {
        if (!cfg.enrollVerify) return true to null
        val bitmap = ImageUtil.decodeFile(saved) ?: return false to null
        val faces = engine.detect(bitmap)
        if (faces.isEmpty()) {
            bitmap.recycle()
            return false to null
        }
        val face = faces.maxByOrNull { it.w * it.h }!!
        val again = engine.descriptor(bitmap, face)
        bitmap.recycle()
        if (again == null) return false to null
        val similarity = engine.similarity(descriptor, again)
        return (similarity >= 0.5f) to similarity
    }

    private fun removePerson(folder: File, name: String): Int {
        var removed = 0
        folder.listFiles()?.forEach { file ->
            if (file.isFile && Models.isImage(file.name) && Models.personFromFileName(file.name) == name) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    /** 过程图 (原图 / 掩码 / 抠像结果) 存到 logs/enroll/, 便于排查。 */
    private fun saveDebug(name: String, patch: Bitmap, alpha: FloatArray, face: Face): List<String> {
        val saved = ArrayList<String>()
        try {
            debugDir.mkdirs()
            val base = "${stampFormat.format(Date())}_$name"
            writeJpeg(File(debugDir, "${base}_1原图.jpg"), patch)?.let { saved.add(it) }

            val w = patch.width
            val h = patch.height
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                val value = ((alpha.getOrElse(i) { 1f }.coerceIn(0f, 1f)) * 255).toInt()
                pixels[i] = Color.argb(255, value, value, value)
            }
            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            mask.setPixels(pixels, 0, w, 0, 0, w, h)
            writePng(File(debugDir, "${base}_2掩码.png"), mask)?.let { saved.add(it) }
            mask.recycle()

            val cut = matting.composeOn(patch, alpha, Color.rgb(32, 32, 32))
            writeJpeg(File(debugDir, "${base}_3抠像.jpg"), cut)?.let { saved.add(it) }
            cut.recycle()
        } catch (e: Throwable) {
            Log.w(TAG, "过程图保存失败: ${e.message}")
        }
        return saved
    }

    private fun writeJpeg(file: File, bitmap: Bitmap): String? = try {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    } catch (e: Throwable) {
        null
    }

    private fun writePng(file: File, bitmap: Bitmap): String? = try {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    } catch (e: Throwable) {
        null
    }

    /** 人脸库概况, 例如 “张三(2张), 李四(1张)”。 */
    fun summaryText(): String {
        val people = personFiles()
        if (people.isEmpty()) return "人脸库为空"
        return people.entries.sortedBy { it.key }
            .joinToString(", ") { "${it.key}(${it.value.size}张)" }
    }

    /** 删除人脸库里的一张照片 (只允许删人脸库目录下的图片)。 */
    fun deletePhoto(fileName: String): Pair<Boolean, String> {
        val safe = File(fileName).name
        if (safe.isEmpty() || !Models.isImage(safe)) return false to "文件名不合法"
        val target = File(cfg.photoDir, safe)
        if (!target.isFile) return false to "找不到文件: $safe"
        return if (target.delete()) {
            log("[录入] 已删除照片: $safe")
            true to "已删除 $safe"
        } else {
            false to "删除失败: $safe"
        }
    }
}

/** 小工具: 保证临时 Bitmap 一定被回收。 */
object BitmapHolder {
    fun <T> use(bitmap: Bitmap, block: (Bitmap) -> T): T = try {
        block(bitmap)
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
