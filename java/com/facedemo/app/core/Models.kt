package com.facedemo.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File

/** 模型文件名与资产目录 (与 Python 版 models/ 里的三个 ONNX 完全一致)。 */
object Models {
    const val YUNET = "face_detection_yunet_2023mar.onnx"
    const val SFACE = "face_recognition_sface_2021dec.onnx"
    const val HUMAN_SEG = "human_segmentation_pphumanseg_2023mar.onnx"
    const val ASSET_DIR = "models"

    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".bmp", ".webp")

    fun isImage(name: String): Boolean =
        IMAGE_EXT.any { name.lowercase().endsWith(it) }

    /** 从文件名取人名: 去掉扩展名和结尾序号 (张三_1.png -> 张三)。 */
    fun personFromFileName(fileName: String): String {
        var stem = fileName.substringBeforeLast('.')
        val match = Regex("^(.*?)[\\s_\\-.]+\\d+$").find(stem)
        if (match != null && match.groupValues[1].isNotBlank()) stem = match.groupValues[1]
        return stem.trim()
    }

    /**
     * 首次启动时把 assets/models 下的 ONNX 复制到 filesDir/models。
     * OpenCV 的 FaceDetectorYN/FaceRecognizerSF 需要真实文件路径。
     */
    fun ensureModels(context: Context, dir: File): List<String> {
        dir.mkdirs()
        val copied = ArrayList<String>()
        for (name in listOf(YUNET, SFACE, HUMAN_SEG)) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 100_000) continue
            try {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
                copied.add(name)
                Log.i("Models", "已释放模型 $name (${target.length() / 1048576} MB)")
            } catch (e: Throwable) {
                Log.e("Models", "释放模型失败 $name: ${e.message}")
            }
        }
        return copied
    }
}

/** 从字节数组/文件解码 Bitmap, 按需缩放避免大图 OOM, 并按 EXIF 把照片转正。 */
object ImageUtil {

    /**
     * 读取 EXIF 旋转角度。
     *
     * `BitmapFactory` **不会**理会 EXIF 方向，而手机拍的照片通常只是把方向记在 EXIF 里
     * （竖着拍的图，像素其实是横的）。直接解码出来就是躺倒的，人脸检测会明显变差。
     * 这里读出来手动转正；读不到就当 0（等价于旧行为，不会更糟）。
     */
    private fun exifDegrees(source: Any): Int = try {
        val exif = when (source) {
            is File -> ExifInterface(source.absolutePath)
            is ByteArray -> ExifInterface(ByteArrayInputStream(source))
            else -> return 0
        }
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (t: Throwable) {
        0
    }

    private fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (t: Throwable) {
            bitmap
        }
    }

    fun decodeScaled(bytes: ByteArray, maxSide: Int = 1600): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            upright(bitmap, exifDegrees(bytes))
        } catch (t: Throwable) {
            null
        }
    }

    fun decodeFile(file: File, maxSide: Int = 1600): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            upright(bitmap, exifDegrees(file))
        } catch (t: Throwable) {
            null
        }
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}
