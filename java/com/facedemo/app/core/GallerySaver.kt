package com.facedemo.app.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 把应用私有目录里的照片「保存到本地」（v1.0.2 新增）。
 *
 * 为什么需要它：人脸库照片原本只存在 `filesDir/photo/`，那是应用私有目录 ——
 * 系统相册看不到、文件管理器也翻不到，老师想单独拿一张照片出来（做名牌、发家长）只能靠分享。
 *
 * 两条路：
 *   * Android 10 (API 29) 及以上：用 MediaStore 写进系统相册的 `Pictures/人脸识别/`，
 *     **不需要任何存储权限**；保存完在图库 App 里就能看到。
 *   * Android 9 及以下：写公共 Pictures 目录需要 WRITE_EXTERNAL_STORAGE，
 *     为了不额外要权限，退回写应用外部私有目录 `Android/data/<包名>/files/Pictures/人脸识别/`
 *     （文件管理器/插电脑能取出，但不进系统相册）。
 */
object GallerySaver {

    private const val TAG = "GallerySaver"
    private const val ALBUM = "人脸识别"

    /** 保存结果：ok + 给人看的一句话（成功时带上保存位置）。 */
    fun saveImage(context: Context, source: File, displayName: String = source.name): Pair<Boolean, String> {
        if (!source.isFile) return false to "照片不存在：${source.name}"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(context, source, displayName)
            else saveToAppPictures(context, source, displayName)
        } catch (t: Throwable) {
            Log.e(TAG, "保存失败", t)
            false to "保存失败：${t.javaClass.simpleName} ${t.message}"
        }
    }

    private fun saveViaMediaStore(context: Context, source: File, displayName: String): Pair<Boolean, String> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(displayName))
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + ALBUM
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false to "系统相册拒绝了写入（可能存储空间不足）"
        try {
            val out = resolver.openOutputStream(uri)
                ?: return false to "打不开相册写入流"
            out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        } catch (t: Throwable) {
            return false to "写入相册失败：${t.message}"
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true to "已保存到相册：Pictures/$ALBUM/$displayName"
    }

    private fun saveToAppPictures(context: Context, source: File, displayName: String): Pair<Boolean, String> {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
        val target = File(dir, displayName)
        FileOutputStream(target).use { out -> source.inputStream().use { it.copyTo(out) } }
        return true to "已保存：${target.absolutePath}"
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}
