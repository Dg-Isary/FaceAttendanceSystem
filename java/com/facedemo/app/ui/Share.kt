package com.facedemo.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把文本/CSV 存到应用缓存目录并调起系统分享 (微信、邮件、网盘都行)。
 *
 * 之所以要落到文件: 名单和点名表都是 CSV, 老师要能直接发出去或存进手机,
 * 而不是只能看屏幕。文件走 FileProvider, 不需要任何存储权限。
 */
fun shareText(context: Context, text: String, fileName: String, mimeType: String = "text/csv") {
    try {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        val target = File(dir, File(fileName).name)
        target.writeText(text, Charsets.UTF_8)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, target.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${target.name}"))
    } catch (e: Throwable) {
        Log.e("Share", "分享失败: ${e.message}")
    }
}

/** 分享一张图片 (例如抠像后的录入结果)。 */
fun shareFile(context: Context, file: File, mimeType: String = "image/png") {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
    } catch (e: Throwable) {
        Log.e("Share", "分享图片失败: ${e.message}")
    }
}
